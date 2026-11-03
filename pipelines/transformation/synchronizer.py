"""Time synchronization and multimodal windowing between accelerometer and heart rate.

Implements:
- DE-005: Strictly causal HR alignment against the prediction cutoff (the event timestamp of the last
  accelerometer sample in the window, never ``end_utc + 1000`` and never the nominal bin end).
- DE-006: Discontinuity-aware event-time windowing that never bridges a >250 ms timestamp gap and reuses
  the authoritative coverage / tensor logic from :mod:`ml.data.window_builder`.
- DE-007: Real temporal coverage, raw vs. model-tensor sample counts, resampling and gap diagnostics.

Online vs. offline semantics:
- The Android runtime :class:`SlidingWindowSegmenter` emits a window as soon as 20 contiguous samples
  (no inter-sample gap > 250 ms) have arrived.
- Offline event-time windowing anchors 1,000 ms bins ``[w_start_utc, w_start_utc + 1000)`` to the start
  of each contiguous fragment (split whenever consecutive samples are separated by > 250 ms). A bin with
  19..21 samples and ``coverage_fraction >= 0.85`` is resampled via :func:`construct_window_tensor` to a
  20-sample model tensor while keeping ``raw_sample_count`` equal to the actual number of observations.
- Both paths share the invariants tested in ``tests/data``: no window crosses a >250 ms gap, no HR sample
  after ``prediction_cutoff_utc`` is used, and coverage reflects actual temporal observations.
"""

from __future__ import annotations

import itertools
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

import numpy as np
import polars as pl

from ml.data.window_builder import construct_window_tensor
from pipelines.validation.schemas import MOTION_CLASS_LABELS, UNLABELED


@dataclass
class WindowSyncDiagnostics:
    """Audit trail produced by :meth:`SensorSynchronizer.synchronize_with_diagnostics`."""

    accepted_windows: list[dict[str, Any]] = field(default_factory=list)
    rejected_windows: list[dict[str, Any]] = field(default_factory=list)
    inter_fragment_gaps_ms: list[int] = field(default_factory=list)
    duplicate_timestamps_dropped: int = 0
    out_of_order_reordered: int = 0

    @property
    def all_windows(self) -> list[dict[str, Any]]:
        return self.accepted_windows + self.rejected_windows

    def to_dict(self) -> dict[str, Any]:
        return {
            "accepted_windows_count": len(self.accepted_windows),
            "rejected_windows_count": len(self.rejected_windows),
            "inter_fragment_gaps_count": len(self.inter_fragment_gaps_ms),
            "inter_fragment_gaps_ms": list(self.inter_fragment_gaps_ms),
            "max_inter_fragment_gap_ms": max(self.inter_fragment_gaps_ms) if self.inter_fragment_gaps_ms else 0,
            "duplicate_timestamps_dropped": self.duplicate_timestamps_dropped,
            "out_of_order_reordered": self.out_of_order_reordered,
        }


def align_causal_heart_rate(
    prediction_cutoff_utc: int,
    hr_times: np.ndarray,
    hr_bpms: np.ndarray,
    hr_sources: list[str],
    max_hr_staleness_ms: int = 10_000,
) -> tuple[float | None, bool, str, int | None, str | None]:
    """Selects the latest HR sample satisfying ``0 <= prediction_cutoff_utc - hr_utc <= max_hr_staleness_ms``.

    Returns ``(bpm, hr_available, hr_source, hr_utc_millis, quality_flag)``.
    """
    if len(hr_times) == 0:
        return None, False, "NONE", None, "NO_HEART_RATE_SAMPLES"

    idx = int(np.searchsorted(hr_times, prediction_cutoff_utc, side="right") - 1)
    if idx < 0:
        return None, False, "NONE", None, "NO_PRIOR_HEART_RATE"

    hr_utc = int(hr_times[idx])
    age_ms = prediction_cutoff_utc - hr_utc
    if 0 <= age_ms <= max_hr_staleness_ms:
        return float(hr_bpms[idx]), True, hr_sources[idx], hr_utc, None
    return None, False, "NONE", None, "STALE_HEART_RATE"


def _dominant(values: list[str | None]) -> tuple[str | None, bool]:
    present = [v for v in values if v is not None and str(v).strip() != ""]
    if not present:
        return None, False
    counts = Counter(present)
    winner = max(counts.items(), key=lambda kv: (kv[1], -present.index(kv[0])))[0]
    return winner, len(counts) > 1


def _resolve_window_motion_label(rows: list[dict[str, Any]]) -> str:
    labels = [r.get("motion_class_label") for r in rows if r.get("motion_class_label") in MOTION_CLASS_LABELS]
    if not labels or len(labels) < len(rows):
        return UNLABELED
    winner, _ = _dominant(labels)
    return winner if winner in MOTION_CLASS_LABELS else UNLABELED


class SensorSynchronizer:
    """Synchronizes high-frequency accelerometer samples with lower-frequency BLE heart rate."""

    last_diagnostics: WindowSyncDiagnostics | None = None

    @staticmethod
    def synchronize_with_diagnostics(
        accel_df: pl.DataFrame,
        hr_df: pl.DataFrame,
        window_size: int = 20,
        max_hr_staleness_ms: int = 10_000,
        max_inter_sample_gap_ms: int = 250,
        window_duration_ms: int = 1_000,
        min_coverage_fraction: float = 0.85,
    ) -> WindowSyncDiagnostics:
        diag = WindowSyncDiagnostics()
        if accel_df.is_empty():
            SensorSynchronizer.last_diagnostics = diag
            return diag

        sessions = accel_df["session_id"].unique(maintain_order=True).to_list()
        has_hr = not hr_df.is_empty() and {"session_id", "utc_millis", "bpm"}.issubset(set(hr_df.columns))
        bin_width_ms = window_duration_ms / window_size

        for session_id in sessions:
            s_df = accel_df.filter(pl.col("session_id") == session_id)
            raw_rows = s_df.to_dicts()
            # Filter out any non-finite rows defensively
            raw_rows = [
                r
                for r in raw_rows
                if r.get("utc_millis") is not None
                and r.get("elapsed_nanos") is not None
                and np.isfinite(r.get("x", np.nan))
                and np.isfinite(r.get("y", np.nan))
                and np.isfinite(r.get("z", np.nan))
            ]
            if not raw_rows:
                continue

            # Detect out-of-order timestamps before sorting
            elapsed_seq = [int(r["elapsed_nanos"]) for r in raw_rows]
            reordered = any(b < a for a, b in itertools.pairwise(elapsed_seq))
            if reordered:
                diag.out_of_order_reordered += 1
            raw_rows.sort(key=lambda r: (int(r["elapsed_nanos"]), int(r["utc_millis"])))

            # Deduplicate identical timestamps (keep first)
            deduped: list[dict[str, Any]] = []
            seen_ts: set[tuple[int, int]] = set()
            dup_in_session = False
            for r in raw_rows:
                key = (int(r["elapsed_nanos"]), int(r["utc_millis"]))
                if key in seen_ts or (deduped and (
                    int(r["elapsed_nanos"]) == int(deduped[-1]["elapsed_nanos"])
                    or int(r["utc_millis"]) == int(deduped[-1]["utc_millis"])
                )):
                    diag.duplicate_timestamps_dropped += 1
                    dup_in_session = True
                    continue
                seen_ts.add(key)
                deduped.append(r)

            # Prepare session HR sorted by utc_millis (only same session_id!)
            if has_hr:
                s_hr = (
                    hr_df.filter(
                        (pl.col("session_id") == session_id)
                        & pl.col("utc_millis").is_not_null()
                        & pl.col("bpm").is_not_null()
                        & (pl.col("bpm") >= 30)
                        & (pl.col("bpm") <= 240)
                    )
                    .sort("utc_millis")
                )
                hr_times = s_hr["utc_millis"].to_numpy().astype(np.int64) if len(s_hr) > 0 else np.array([], dtype=np.int64)
                hr_bpms = s_hr["bpm"].to_numpy().astype(np.float32) if len(s_hr) > 0 else np.array([], dtype=np.float32)
                hr_sources = (
                    s_hr["source_kind"].to_list()
                    if len(s_hr) > 0 and "source_kind" in s_hr.columns
                    else ["BLE_REAL"] * len(s_hr)
                )
            else:
                hr_times = np.array([], dtype=np.int64)
                hr_bpms = np.array([], dtype=np.float32)
                hr_sources = []

            # Split into contiguous fragments whenever consecutive sample gap > max_inter_sample_gap_ms
            fragments: list[tuple[list[dict[str, Any]], int | None]] = []
            current_frag: list[dict[str, Any]] = [deduped[0]]
            preceding_gap: int | None = None
            for prev, cur in itertools.pairwise(deduped):
                dt_utc = int(cur["utc_millis"]) - int(prev["utc_millis"])
                dt_elapsed_ms = round((int(cur["elapsed_nanos"]) - int(prev["elapsed_nanos"])) / 1_000_000.0)
                gap_ms = max(dt_utc, dt_elapsed_ms)
                if gap_ms > max_inter_sample_gap_ms:
                    diag.inter_fragment_gaps_ms.append(gap_ms)
                    fragments.append((current_frag, preceding_gap))
                    current_frag = [cur]
                    preceding_gap = gap_ms
                else:
                    current_frag.append(cur)
            fragments.append((current_frag, preceding_gap))

            w_counter = 0
            for frag_rows, frag_gap_ms in fragments:
                frag_start_utc = int(frag_rows[0]["utc_millis"])
                frag_last_utc = int(frag_rows[-1]["utc_millis"])
                frag_times = np.array([int(r["utc_millis"]) for r in frag_rows], dtype=np.int64)
                n_bins = max(1, int((frag_last_utc - frag_start_utc) // window_duration_ms) + 1)

                for b_idx in range(n_bins):
                    w_start_utc = frag_start_utc + b_idx * window_duration_ms
                    w_end_utc = w_start_utc + window_duration_ms
                    left = int(np.searchsorted(frag_times, w_start_utc, side="left"))
                    right = int(np.searchsorted(frag_times, w_end_utc, side="left"))
                    w_rows = frag_rows[left:right]
                    if not w_rows:
                        continue

                    sample_ts = np.array([int(r["utc_millis"]) for r in w_rows], dtype=np.int64)
                    sample_elapsed = np.array([int(r["elapsed_nanos"]) for r in w_rows], dtype=np.int64)
                    raw_count = len(w_rows)
                    flags: list[str] = []
                    if reordered:
                        flags.append("OUT_OF_ORDER_REORDERED")
                    if dup_in_session:
                        flags.append("DUPLICATE_SAMPLE_TIMESTAMP")
                    if b_idx == 0 and frag_gap_ms is not None:
                        flags.append("PRECEDED_BY_STREAM_GAP")

                    inner_gaps = np.diff(sample_ts) if raw_count > 1 else np.array([], dtype=np.int64)
                    edge_start = int(sample_ts[0] - w_start_utc)
                    edge_end = int(w_end_utc - sample_ts[-1])
                    all_gaps = [edge_start, edge_end] + inner_gaps.tolist()
                    max_gap_ms = int(max(all_gaps))
                    has_large_gap = max_gap_ms > max_inter_sample_gap_ms
                    if has_large_gap:
                        flags.append("LARGE_GAP")

                    bin_indices = np.floor((sample_ts - w_start_utc) / bin_width_ms).astype(int)
                    valid_bins = bin_indices[(bin_indices >= 0) & (bin_indices < window_size)]
                    covered_bins = len(np.unique(valid_bins))
                    coverage_fraction = round(float(covered_bins / window_size), 4)
                    if coverage_fraction < min_coverage_fraction:
                        flags.append("LOW_COVERAGE")

                    signal_eligible = (not has_large_gap) and (coverage_fraction >= min_coverage_fraction)

                    xyz_raw = np.column_stack([
                        np.array([float(r["x"]) for r in w_rows], dtype=np.float32),
                        np.array([float(r["y"]) for r in w_rows], dtype=np.float32),
                        np.array([float(r["z"]) for r in w_rows], dtype=np.float32),
                    ])
                    tensor, was_resampled = construct_window_tensor(
                        w_start_utc=w_start_utc,
                        w_end_utc=w_end_utc,
                        sample_ts=sample_ts,
                        sample_xyz=xyz_raw,
                        target_sampling_rate_hz=window_size,
                        max_gap_tolerance_ms=80,
                        training_eligible=signal_eligible,
                    )

                    # Strict causal HR alignment against the LAST accelerometer event timestamp in this window
                    prediction_cutoff_utc = int(sample_ts[-1])
                    mean_bpm, hr_avail, hr_source, hr_utc, hr_flag = align_causal_heart_rate(
                        prediction_cutoff_utc=prediction_cutoff_utc,
                        hr_times=hr_times,
                        hr_bpms=hr_bpms,
                        hr_sources=hr_sources,
                        max_hr_staleness_ms=max_hr_staleness_ms,
                    )
                    if hr_flag:
                        flags.append(hr_flag)

                    gt_activity, has_transition = _dominant([r.get("ground_truth_activity") for r in w_rows])
                    if has_transition:
                        flags.append("ACTIVITY_TRANSITION")
                    pred_motion, _ = _dominant([r.get("predicted_motion_class") for r in w_rows])
                    motion_label = _resolve_window_motion_label(w_rows)
                    supervised_eligible = signal_eligible and (motion_label in MOTION_CLASS_LABELS)
                    if not (motion_label in MOTION_CLASS_LABELS):
                        flags.append("UNLABELED_WINDOW")

                    window_dict = {
                        "window_id": f"{session_id}-w{w_counter:05d}",
                        "session_id": session_id,
                        "start_utc_millis": int(sample_ts[0]),
                        "end_utc_millis": prediction_cutoff_utc,
                        "window_start_utc": w_start_utc,
                        "window_end_utc": w_end_utc,
                        "prediction_cutoff_utc": prediction_cutoff_utc,
                        "start_elapsed_nanos": int(sample_elapsed[0]),
                        "end_elapsed_nanos": int(sample_elapsed[-1]),
                        "raw_sample_count": raw_count,
                        "sample_count": raw_count,
                        "model_tensor_sample_count": int(tensor.shape[0]),
                        "coverage_fraction": coverage_fraction,
                        "max_gap_ms": max_gap_ms,
                        "has_large_gap": bool(has_large_gap),
                        "was_resampled": bool(was_resampled),
                        "signal_eligible": bool(signal_eligible),
                        "training_eligible": bool(supervised_eligible),
                        "quality_flags": ",".join(sorted(set(flags))),
                        "preceding_stream_gap_ms": frag_gap_ms if b_idx == 0 else None,
                        "accel_x": tensor[:, 0].astype(float).tolist(),
                        "accel_y": tensor[:, 1].astype(float).tolist(),
                        "accel_z": tensor[:, 2].astype(float).tolist(),
                        "raw_accel_x": xyz_raw[:, 0].astype(float).tolist(),
                        "raw_accel_y": xyz_raw[:, 1].astype(float).tolist(),
                        "raw_accel_z": xyz_raw[:, 2].astype(float).tolist(),
                        "raw_elapsed_nanos": sample_elapsed.tolist(),
                        "raw_utc_millis": sample_ts.tolist(),
                        "raw_accuracies": [int(r.get("accuracy", 3)) for r in w_rows],
                        "mean_hr": mean_bpm,
                        "hr_available": hr_avail,
                        "hr_utc_millis": hr_utc,
                        "hr_source": hr_source,
                        "provenance": str(w_rows[0].get("source_kind", "PHONE_REAL")),
                        "ground_truth_activity": gt_activity,
                        "predicted_motion_class": pred_motion,
                        "motion_class_label": motion_label,
                    }
                    w_counter += 1
                    if signal_eligible:
                        diag.accepted_windows.append(window_dict)
                    else:
                        diag.rejected_windows.append(window_dict)

        SensorSynchronizer.last_diagnostics = diag
        return diag

    @staticmethod
    def create_synchronized_windows(
        accel_df: pl.DataFrame,
        hr_df: pl.DataFrame,
        window_size: int = 20,
        max_hr_staleness_ms: int = 10_000,
        max_inter_sample_gap_ms: int = 250,
        window_duration_ms: int = 1_000,
        min_coverage_fraction: float = 0.85,
        include_ineligible: bool = False,
    ) -> list[dict[str, Any]]:
        """Segments accelerometer into discontinuity-safe windows and aligns causal HR measurements."""
        diag = SensorSynchronizer.synchronize_with_diagnostics(
            accel_df=accel_df,
            hr_df=hr_df,
            window_size=window_size,
            max_hr_staleness_ms=max_hr_staleness_ms,
            max_inter_sample_gap_ms=max_inter_sample_gap_ms,
            window_duration_ms=window_duration_ms,
            min_coverage_fraction=min_coverage_fraction,
        )
        return diag.all_windows if include_ineligible else diag.accepted_windows
