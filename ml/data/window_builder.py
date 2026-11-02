"""Authoritative Event-Time Window Builder and Feature Extraction Engine.

Implements strict timestamp-anchored half-open event-time windowing [w_start_ms, w_end_ms),
sub-bin temporal coverage calculation, gap/dropout detection, monotonic sensor alignment,
causal heart-rate synchronization without lookahead leakage, and quality gating.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Sequence

import numpy as np


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
    4. Causal HR: Only samples with utc_millis <= w_end_utc are considered; signed staleness <= 10s.
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
            int_starts.append(session_start_ms + int(round(it.start_sec * 1000)))
            int_ends.append(session_start_ms + int(round(it.end_sec * 1000)))
            int_labels.append(getattr(it, "state_name", getattr(it, "state_label", "REST")))
        elif isinstance(it, dict):
            int_starts.append(it["start_utc_millis"])
            int_ends.append(it["end_utc_millis"])
            int_labels.append(it.get("state_name", it.get("state_label", "REST")))

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

        # Causal Heart Rate Alignment (prediction cutoff is w_end_utc)
        # MUST satisfy: hr_utc_millis <= w_end_utc
        mean_hr: float | None = None
        hr_available = 0
        norm_bpm = 0.0
        hr_mask = 0.0

        if len(hr_times) > 0:
            # Search for latest HR sample at or before w_end_utc
            hr_idx = np.searchsorted(hr_times, w_end_utc, side="right") - 1
            if hr_idx >= 0:
                latest_hr_time = int(hr_times[hr_idx])
                latest_bpm = float(hr_bpms[hr_idx])
                # Signed age: prediction_time - hr_time
                hr_age_ms = w_end_utc - latest_hr_time
                if 0 <= hr_age_ms <= policy.max_hr_staleness_ms:
                    mean_hr = round(latest_bpm, 1)
                    hr_available = 1
                    norm_bpm = round(float((latest_bpm - 60.0) / 40.0), 4)
                    hr_mask = 1.0
                else:
                    quality_flags.append("STALE_HEART_RATE")
            else:
                quality_flags.append("NO_PRIOR_HEART_RATE")
        else:
            quality_flags.append("NO_HEART_RATE_SAMPLES")

        # Ground Truth state from maximum interval overlap
        best_overlap_ms = 0
        gt_label = "REST"
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

        # Resampling to model-ready [20, 3] tensor
        target_grid = w_start_utc + (np.arange(policy.target_sampling_rate_hz) + 0.5) * policy.nominal_interval_ms
        was_resampled = 0

        if training_eligible and len(unique_samples) >= 2:
            xs_raw = np.array([s["x"] for s in unique_samples], dtype=np.float32)
            ys_raw = np.array([s["y"] for s in unique_samples], dtype=np.float32)
            zs_raw = np.array([s["z"] for s in unique_samples], dtype=np.float32)

            if len(unique_samples) == policy.target_sampling_rate_hz and max_gap_ms <= 80:
                # Direct placement
                resampled_tensor = np.column_stack([xs_raw, ys_raw, zs_raw]).astype(np.float32)
            else:
                # Controlled linear time-interpolation
                was_resampled = 1
                interp_x = np.interp(target_grid, sample_ts, xs_raw).astype(np.float32)
                interp_y = np.interp(target_grid, sample_ts, ys_raw).astype(np.float32)
                interp_z = np.interp(target_grid, sample_ts, zs_raw).astype(np.float32)
                resampled_tensor = np.column_stack([interp_x, interp_y, interp_z]).astype(np.float32)
        elif len(unique_samples) > 0:
            # Fallback zero-padded/edge tensor for rejected windows (not training eligible)
            xs_raw = np.array([s["x"] for s in unique_samples], dtype=np.float32)
            ys_raw = np.array([s["y"] for s in unique_samples], dtype=np.float32)
            zs_raw = np.array([s["z"] for s in unique_samples], dtype=np.float32)
            pad_len = max(0, policy.target_sampling_rate_hz - len(xs_raw))
            resampled_tensor = np.column_stack([
                np.pad(xs_raw[:policy.target_sampling_rate_hz], (0, pad_len), mode="edge"),
                np.pad(ys_raw[:policy.target_sampling_rate_hz], (0, pad_len), mode="edge"),
                np.pad(zs_raw[:policy.target_sampling_rate_hz], (0, pad_len), mode="edge"),
            ]).astype(np.float32)
        else:
            resampled_tensor = np.zeros((policy.target_sampling_rate_hz, 3), dtype=np.float32)

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
