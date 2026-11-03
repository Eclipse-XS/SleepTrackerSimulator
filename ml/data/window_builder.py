"""Authoritative Event-Time Window Builder and Feature Extraction Engine.

Implements strict timestamp-anchored half-open event-time windowing [w_start_ms, w_end_ms),
sub-bin temporal coverage calculation, gap/dropout detection, monotonic sensor alignment,
causal heart-rate synchronization without lookahead leakage, and quality gating.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Sequence

import numpy as np

UNLABELED_LABEL = "UNLABELED"


@dataclass(frozen=True)
class WindowQualityPolicy:
    """Configurable quality policy for window construction and model readiness."""

    window_duration_ms: int = 1000
    target_sampling_rate_hz: int = 20
    nominal_interval_ms: float = 50.0  # 1000 / 20
    min_coverage_fraction: float = 0.85
    max_inter_sample_gap_ms: int = 250
    max_hr_staleness_ms: int = 10_000
    min_hr_bpm: int = 30
    max_hr_bpm: int = 240
    num_coverage_bins: int = 20


@dataclass
class WindowBuildResult:
    """Contains constructed feature window dictionaries and model-ready tensors."""

    windows: list[dict[str, Any]] = field(default_factory=list)
    model_tensors: list[np.ndarray] = field(default_factory=list)
    hr_feature_vectors: list[list[float]] = field(default_factory=list)
    tabular_features: list[list[float]] = field(default_factory=list)
    rejected_count: int = 0
    accepted_count: int = 0


def construct_window_tensor(
    w_start_utc: int,
    w_end_utc: int,
    sample_ts: Sequence[int] | np.ndarray,
    sample_xyz: Sequence[Sequence[float]] | np.ndarray,
    target_sampling_rate_hz: int = 20,
    max_gap_tolerance_ms: int = 80,
    training_eligible: bool = True,
) -> tuple[np.ndarray, int]:
    """Deterministically constructs a [target_sampling_rate_hz, 3] float32 tensor from raw observations.

    Guarantees strict numerical parity and byte-identical outputs between WindowBuilder and WindowDataset.

    Args:
        w_start_utc: Start timestamp in epoch milliseconds (inclusive).
        w_end_utc: End timestamp in epoch milliseconds (exclusive).
        sample_ts: Array or sequence of raw timestamps.
        sample_xyz: Array or sequence of 3D raw acceleration samples (x, y, z).
        target_sampling_rate_hz: Number of samples per window (default: 20).
        max_gap_tolerance_ms: Maximum gap allowed before falling back to linear interpolation (default: 80).
        training_eligible: Whether the window meets quality standards.

    Returns:
        (tensor, was_resampled):
            - tensor: np.ndarray of shape [target_sampling_rate_hz, 3] and dtype np.float32.
            - was_resampled: 1 if linear interpolation was applied, 0 otherwise.
    """
    ts_arr = np.asarray(sample_ts, dtype=np.int64) if len(sample_ts) > 0 else np.array([], dtype=np.int64)
    xyz_arr = np.asarray(sample_xyz, dtype=np.float32) if len(sample_xyz) > 0 else np.empty((0, 3), dtype=np.float32)

    if len(ts_arr) == 0 or len(xyz_arr) == 0:
        return np.zeros((target_sampling_rate_hz, 3), dtype=np.float32), 0

    # Deduplicate consecutive identical timestamps (keep first occurrence)
    if len(ts_arr) > 1:
        mask = np.empty(len(ts_arr), dtype=bool)
        mask[0] = True
        mask[1:] = ts_arr[1:] != ts_arr[:-1]
        ts_clean = ts_arr[mask]
        xyz_clean = xyz_arr[mask]
    else:
        ts_clean = ts_arr
        xyz_clean = xyz_arr

    # Compute inter-sample and boundary gaps
    inner_gaps = np.diff(ts_clean) if len(ts_clean) > 1 else np.array([], dtype=np.int64)
    edge_gap_start = int(ts_clean[0] - w_start_utc)
    edge_gap_end = int(w_end_utc - ts_clean[-1])
    all_gaps = [edge_gap_start, edge_gap_end] + inner_gaps.tolist()
    max_gap_ms = int(max(all_gaps))

    nominal_interval_ms = (w_end_utc - w_start_utc) / target_sampling_rate_hz
    target_grid = w_start_utc + (np.arange(target_sampling_rate_hz, dtype=np.float64) + 0.5) * nominal_interval_ms

    if training_eligible and len(ts_clean) >= 2:
        if len(ts_clean) == target_sampling_rate_hz and max_gap_ms <= max_gap_tolerance_ms:
            # Direct placement: exactly target_sampling_rate_hz samples with uniform spacing
            return xyz_clean.astype(np.float32), 0
        else:
            # Controlled linear time-interpolation
            interp_x = np.interp(target_grid, ts_clean, xyz_clean[:, 0]).astype(np.float32)
            interp_y = np.interp(target_grid, ts_clean, xyz_clean[:, 1]).astype(np.float32)
            interp_z = np.interp(target_grid, ts_clean, xyz_clean[:, 2]).astype(np.float32)
            return np.column_stack([interp_x, interp_y, interp_z]).astype(np.float32), 1
    elif len(ts_clean) > 0:
        pad_len = max(0, target_sampling_rate_hz - len(xyz_clean))
        tensor = np.pad(xyz_clean[:target_sampling_rate_hz], ((0, pad_len), (0, 0)), mode="edge").astype(np.float32)
        return tensor, 0
    else:
        return np.zeros((target_sampling_rate_hz, 3), dtype=np.float32), 0


def build_session_windows(
    session_id: str,
    session_start_ms: int,
    session_end_ms: int,
    raw_accelerometer_samples: list[dict[str, Any]],
    raw_heart_rate_samples: list[dict[str, Any]],
    ground_truth_intervals: Sequence[Any],
    policy: WindowQualityPolicy | None = None,
) -> WindowBuildResult:
    """Constructs authoritative event-time feature windows anchored to the session timeline.

    Rules:
    1. Timeline intervals are half-open: [w_start_utc, w_end_utc).
    2. Coverage is calculated using event-time sub-bins (no hardcoded 1.0).
    3. Dropouts / large gaps are detected and flag windows as training-ineligible.
    4. Causal HR: prediction cutoff = event time of the last accelerometer sample in the window; only HR with
       hr_utc <= cutoff and cutoff - hr_utc <= max_hr_staleness_ms is eligible (latest one wins).
    5. Model-ready [20, 3] tensors are produced via controlled resampling for training-eligible windows.
    6. Ground-truth label is assigned via maximum temporal interval overlap.
    """
    if policy is None:
        policy = WindowQualityPolicy()

    result = WindowBuildResult()
    duration_ms = session_end_ms - session_start_ms
    if duration_ms < policy.window_duration_ms:
        return result

    # 1. Sort and index raw accelerometer samples
    # Support dictionary keys: 'utc_millis', 'x', 'y', 'z', and optional 'elapsed_nanos', 'accuracy'
    clean_accel = [
        s for s in raw_accelerometer_samples
        if s.get("utc_millis") is not None
        and np.isfinite(s.get("x", np.nan))
        and np.isfinite(s.get("y", np.nan))
        and np.isfinite(s.get("z", np.nan))
    ]
    clean_accel.sort(key=lambda s: s["utc_millis"])

    accel_times = np.array([s["utc_millis"] for s in clean_accel], dtype=np.int64) if clean_accel else np.array([], dtype=np.int64)

    # 2. Sort and index heart rate samples (strictly causal lookup)
    clean_hr = [
        h for h in raw_heart_rate_samples
        if h.get("utc_millis") is not None
        and h.get("bpm") is not None
        and policy.min_hr_bpm <= h["bpm"] <= policy.max_hr_bpm
    ]
    clean_hr.sort(key=lambda h: h["utc_millis"])
    hr_times = np.array([h["utc_millis"] for h in clean_hr], dtype=np.int64) if clean_hr else np.array([], dtype=np.int64)
    hr_bpms = np.array([h["bpm"] for h in clean_hr], dtype=np.float32) if clean_hr else np.array([], dtype=np.float32)

    # 3. Ground truth interval arrays
    # Support intervals with start_sec/end_sec or start_utc_millis/end_utc_millis
    int_starts: list[int] = []
    int_ends: list[int] = []
    int_labels: list[str] = []
    for it in ground_truth_intervals:
        if hasattr(it, "start_sec") and hasattr(it, "end_sec"):
            label = getattr(it, "state_name", None) or getattr(it, "state_label", None)
            if not label:
                raise ValueError(f"Ground-truth interval without a label in session {session_id}: {it!r}")
            int_starts.append(session_start_ms + int(round(it.start_sec * 1000)))
            int_ends.append(session_start_ms + int(round(it.end_sec * 1000)))
            int_labels.append(label)
        elif isinstance(it, dict):
            label = it.get("state_name") or it.get("state_label")
            if not label:
                raise ValueError(f"Ground-truth interval without a label in session {session_id}: {it!r}")
            int_starts.append(it["start_utc_millis"])
            int_ends.append(it["end_utc_millis"])
            int_labels.append(label)

    n_windows = duration_ms // policy.window_duration_ms
    nominal_dt_sec = 1.0 / policy.target_sampling_rate_hz
    bin_width_ms = policy.window_duration_ms / policy.num_coverage_bins

    for w_idx in range(n_windows):
        w_start_utc = session_start_ms + w_idx * policy.window_duration_ms
        w_end_utc = w_start_utc + policy.window_duration_ms
        window_id = f"{session_id}-w{w_idx:05d}"

        # Find accelerometer samples in half-open interval [w_start_utc, w_end_utc)
        if len(accel_times) > 0:
            left_idx = np.searchsorted(accel_times, w_start_utc, side="left")
            right_idx = np.searchsorted(accel_times, w_end_utc, side="left")
            w_raw = clean_accel[left_idx:right_idx]
        else:
            w_raw = []

        obs_count = len(w_raw)
        quality_flags: list[str] = []

        # Deduplicate identical timestamps within window
        unique_samples: list[dict[str, Any]] = []
        last_t = -1
        for s in w_raw:
            t = s["utc_millis"]
            if t != last_t:
                unique_samples.append(s)
                last_t = t
            else:
                quality_flags.append("DUPLICATE_SAMPLE_TIMESTAMP")

        sample_ts = np.array([s["utc_millis"] for s in unique_samples], dtype=np.int64)

        # Gap and coverage analysis
        if len(sample_ts) == 0:
            max_gap_ms = policy.window_duration_ms
            coverage_fraction = 0.0
            has_large_gap = 1
            quality_flags.append("EMPTY_WINDOW")
        else:
            # Measure gaps between consecutive samples and with window boundaries
            inner_gaps = np.diff(sample_ts) if len(sample_ts) > 1 else np.array([], dtype=np.int64)
            edge_gap_start = int(sample_ts[0] - w_start_utc)
            edge_gap_end = int(w_end_utc - sample_ts[-1])
            all_gaps = [edge_gap_start, edge_gap_end] + inner_gaps.tolist()
            max_gap_ms = int(max(all_gaps))
            has_large_gap = 1 if max_gap_ms > policy.max_inter_sample_gap_ms else 0
            if has_large_gap:
                quality_flags.append("LARGE_GAP")

            # Sub-bin temporal coverage
            bin_indices = np.floor((sample_ts - w_start_utc) / bin_width_ms).astype(int)
            valid_bins = bin_indices[(bin_indices >= 0) & (bin_indices < policy.num_coverage_bins)]
            covered_bins = len(np.unique(valid_bins))
            coverage_fraction = round(float(covered_bins / policy.num_coverage_bins), 4)

        if coverage_fraction < policy.min_coverage_fraction:
            quality_flags.append("LOW_COVERAGE")

        # Causal Heart Rate Alignment.
        # The prediction cutoff is the event timestamp of the LAST accelerometer sample included in this
        # window (the moment the Android online segmenter would emit the window), NOT the nominal bin end.
        # An HR sample is eligible iff hr_utc <= cutoff and 0 <= cutoff - hr_utc <= max_hr_staleness_ms.
        mean_hr: float | None = None
        hr_available = 0
        hr_utc_millis: int | None = None
        hr_sample_id: str | None = None
        norm_bpm = 0.0
        hr_mask = 0.0
        prediction_cutoff_utc: int | None = int(sample_ts[-1]) if len(sample_ts) > 0 else None

        if prediction_cutoff_utc is None:
            pass  # no accelerometer observation => no prediction => no HR alignment
        elif len(hr_times) > 0:
            # Latest HR sample at or before the prediction cutoff
            hr_idx = np.searchsorted(hr_times, prediction_cutoff_utc, side="right") - 1
            if hr_idx >= 0:
                latest_hr_time = int(hr_times[hr_idx])
                latest_bpm = float(hr_bpms[hr_idx])
                # Signed age: prediction_cutoff - hr_time
                hr_age_ms = prediction_cutoff_utc - latest_hr_time
                if 0 <= hr_age_ms <= policy.max_hr_staleness_ms:
                    mean_hr = round(latest_bpm, 1)
                    hr_available = 1
                    hr_utc_millis = latest_hr_time
                    hr_sample_id = clean_hr[hr_idx].get("sample_id", f"{session_id}-hr-{hr_idx}")
                    norm_bpm = round(float((latest_bpm - 60.0) / 40.0), 4)
                    hr_mask = 1.0
                else:
                    quality_flags.append("STALE_HEART_RATE")
            else:
                quality_flags.append("NO_PRIOR_HEART_RATE")
        else:
            quality_flags.append("NO_HEART_RATE_SAMPLES")

        # Ground Truth state from maximum interval overlap. Without any overlapping interval the
        # window is explicitly UNLABELED - a missing annotation is never silently turned into REST.
        best_overlap_ms = 0
        gt_label = UNLABELED_LABEL
        for k in range(len(int_starts)):
            overlap_start = max(w_start_utc, int_starts[k])
            overlap_end = min(w_end_utc, int_ends[k])
            overlap_dur = max(0, overlap_end - overlap_start)
            if overlap_dur > best_overlap_ms:
                best_overlap_ms = overlap_dur
                gt_label = int_labels[k]

        # Training eligibility assessment
        critical_flags = {"EMPTY_WINDOW", "LOW_COVERAGE", "LARGE_GAP"}
        training_eligible = 0 if any(f in critical_flags for f in quality_flags) else 1

        # Resampling to model-ready [20, 3] tensor via unified tensor constructor
        if unique_samples:
            xs_raw = np.array([s["x"] for s in unique_samples], dtype=np.float32)
            ys_raw = np.array([s["y"] for s in unique_samples], dtype=np.float32)
            zs_raw = np.array([s["z"] for s in unique_samples], dtype=np.float32)
            xyz_raw = np.column_stack([xs_raw, ys_raw, zs_raw])
        else:
            xyz_raw = np.empty((0, 3), dtype=np.float32)

        resampled_tensor, was_resampled = construct_window_tensor(
            w_start_utc=w_start_utc,
            w_end_utc=w_end_utc,
            sample_ts=sample_ts,
            sample_xyz=xyz_raw,
            target_sampling_rate_hz=policy.target_sampling_rate_hz,
            max_gap_tolerance_ms=80,
            training_eligible=bool(training_eligible),
        )

        # Physical feature extraction on resampled tensor
        dyn_x = resampled_tensor[:, 0] - np.mean(resampled_tensor[:, 0])
        dyn_y = resampled_tensor[:, 1] - np.mean(resampled_tensor[:, 1])
        dyn_z = resampled_tensor[:, 2] - np.mean(resampled_tensor[:, 2])

        mags = np.sqrt(dyn_x**2 + dyn_y**2 + dyn_z**2)
        rms = float(np.sqrt(np.mean(mags**2)))
        variance = float(np.var(mags))
        peak_to_peak = float(np.max(mags) - np.min(mags))
        jerk = np.diff(mags) / nominal_dt_sec
        jerk_rms = float(np.sqrt(np.mean(jerk**2))) if len(jerk) > 0 else 0.0

        flags_str = ",".join(sorted(set(quality_flags)))

        window_dict = {
            "window_id": window_id,
            "session_id": session_id,
            "start_utc_millis": w_start_utc,
            "end_utc_millis": w_end_utc,
            "sample_count": obs_count,
            "coverage_fraction": coverage_fraction,
            "motion_rms": round(rms, 6),
            "variance": round(variance, 6),
            "jerk": round(jerk_rms, 6),
            "peak_to_peak": round(peak_to_peak, 6),
            "mean_hr": mean_hr,
            "hr_available": hr_available,
            "hr_utc_millis": hr_utc_millis,
            "hr_sample_id": hr_sample_id,
            "prediction_cutoff_utc": prediction_cutoff_utc,
            "model_tensor_sample_count": policy.target_sampling_rate_hz,
            "ground_truth_label": gt_label,
            "provenance": "SIMULATED",
            "max_gap_ms": max_gap_ms,
            "has_large_gap": has_large_gap,
            "was_resampled": was_resampled,
            "training_eligible": training_eligible,
            "quality_flags": flags_str,
        }

        result.windows.append(window_dict)
        result.model_tensors.append(resampled_tensor)
        result.hr_feature_vectors.append([norm_bpm, hr_mask])
        result.tabular_features.append([
            round(rms, 6),
            round(variance, 6),
            round(jerk_rms, 6),
            round(peak_to_peak, 6),
            norm_bpm,
            hr_mask,
        ])

        if training_eligible:
            result.accepted_count += 1
        else:
            result.rejected_count += 1

    return result
