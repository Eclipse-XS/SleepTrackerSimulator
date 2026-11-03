"""Feature extraction engine for Android-runtime parity and synthetic ML pipelines.

Contracts (DE/ML-008):
- ``android_runtime_features_v1`` (default): exact mathematical match with Kotlin
  ``com.example.sleeptrackersimulator.domain.signalprocessing.FeatureExtractor``:
  ``dyn = |norm(xyz) - 9.80665|``, RMS of ``dyn``, sample variance of ``dyn`` with ``N - 1``
  denominator, mean absolute magnitude derivative ``|mag[i] - mag[i-1]| / dt_sec`` over intervals
  with ``dt_sec > 0.0001``, and ``peak_to_peak = max(mag) - min(mag)``.
- ``centered_axis_synthetic_v1``: channel-mean-centered vector norm used by the synthetic SQLite
  benchmark in :mod:`ml.data.window_builder`. Kept under an explicit version name so existing
  synthetic models and published benchmark comparisons do not silently change meaning.

Neither contract ever invents a supervised ``REST``/``RESTLESS``/``ACTIVE`` label from RMS/variance
thresholds or from ``predicted_motion_class`` (DE-002, ML-001).
"""

from __future__ import annotations

import itertools
from collections.abc import Sequence
from typing import Any

import numpy as np
import polars as pl

from pipelines.validation.schemas import MOTION_CLASS_LABELS, UNLABELED

ANDROID_RUNTIME_FEATURES_V1 = "android_runtime_features_v1"
CENTERED_AXIS_SYNTHETIC_V1 = "centered_axis_synthetic_v1"
GRAVITY_M_S2 = np.float32(9.80665)


def extract_android_runtime_features_v1(
    xs: Sequence[float] | np.ndarray,
    ys: Sequence[float] | np.ndarray,
    zs: Sequence[float] | np.ndarray,
    elapsed_nanos: Sequence[int] | np.ndarray | None = None,
    accuracies: Sequence[int] | None = None,
    expected_sample_count: int = 20,
    dt_sec: float = 0.05,
) -> dict[str, Any]:
    """Computes the exact Kotlin ``SignalProcessingPipeline.FeatureExtractor`` metrics."""
    x_arr = np.asarray(xs, dtype=np.float32)
    y_arr = np.asarray(ys, dtype=np.float32)
    z_arr = np.asarray(zs, dtype=np.float32)
    count = len(x_arr)
    if count == 0:
        raise ValueError("Cannot extract features from an empty window")

    if elapsed_nanos is not None and len(elapsed_nanos) == count:
        elapsed = np.asarray(elapsed_nanos, dtype=np.int64)
    else:
        step_ns = round(dt_sec * 1_000_000_000)
        elapsed = np.arange(count, dtype=np.int64) * step_ns

    count_coverage = min(1.0, float(count) / float(expected_sample_count))
    pos_intervals = sorted(int(b - a) for a, b in itertools.pairwise(elapsed) if int(b - a) > 0)
    median_interval = pos_intervals[len(pos_intervals) // 2] if pos_intervals else 0
    expected_span = median_interval * (expected_sample_count - 1)
    actual_span = max(0, int(elapsed[-1] - elapsed[0]))
    if expected_span > 0 and actual_span > 0:
        temporal_coverage = min(1.0, float(np.float32(expected_span) / np.float32(actual_span)))
    else:
        temporal_coverage = count_coverage
    coverage = float(np.float32(count_coverage) * np.float32(temporal_coverage))

    mags = np.sqrt(x_arr * x_arr + y_arr * y_arr + z_arr * z_arr).astype(np.float32)
    dyns = np.abs(mags - GRAVITY_M_S2).astype(np.float32)

    sum_dyn = float(np.sum(dyns, dtype=np.float64))
    sum_dyn_sq = float(np.sum(dyns.astype(np.float64) ** 2))
    mean_dyn = np.float32(sum_dyn / count)
    rms_dyn = float(np.float32(np.sqrt(sum_dyn_sq / count)))

    diffs = (dyns - mean_dyn).astype(np.float32)
    variance_dyn = float(np.sum(diffs.astype(np.float64) ** 2))
    variance = float(np.float32(variance_dyn / (count - 1))) if count > 1 else 0.0

    total_jerk = 0.0
    valid_intervals = 0
    for i in range(1, count):
        dt = float(elapsed[i] - elapsed[i - 1]) / 1_000_000_000.0
        if dt > 0.0001:
            d_mag = float(np.abs(mags[i] - mags[i - 1]))
            total_jerk += d_mag / dt
            valid_intervals += 1
    jerk = float(np.float32(total_jerk / valid_intervals)) if valid_intervals > 0 else 0.0
    peak_to_peak = float(np.float32(np.max(mags) - np.min(mags)))

    acc_list = list(accuracies) if accuracies is not None and len(accuracies) == count else [3] * count
    avg_acc = np.float32(np.mean(acc_list))
    acc_fraction = min(np.float32(1.0), avg_acc / np.float32(3.0))
    quality_score = float(np.float32(coverage) * np.float32(0.7) + acc_fraction * np.float32(0.3))

    return {
        "sample_count": count,
        "expected_sample_count": expected_sample_count,
        "coverage_fraction": coverage,
        "motion_rms": rms_dyn,
        "variance": variance,
        "jerk": jerk,
        "peak_to_peak": peak_to_peak,
        "quality_score": quality_score,
        "feature_contract": ANDROID_RUNTIME_FEATURES_V1,
    }


def extract_centered_axis_synthetic_v1(
    xs: Sequence[float] | np.ndarray,
    ys: Sequence[float] | np.ndarray,
    zs: Sequence[float] | np.ndarray,
    dt_sec: float = 0.05,
) -> dict[str, Any]:
    """Channel-mean-centered vector norm features used by the synthetic SQLite benchmark."""
    x_arr = np.asarray(xs, dtype=np.float32)
    y_arr = np.asarray(ys, dtype=np.float32)
    z_arr = np.asarray(zs, dtype=np.float32)
    dyn_x = x_arr - np.mean(x_arr)
    dyn_y = y_arr - np.mean(y_arr)
    dyn_z = z_arr - np.mean(z_arr)
    mags = np.sqrt(dyn_x**2 + dyn_y**2 + dyn_z**2)
    rms = float(np.sqrt(np.mean(mags**2)))
    variance = float(np.var(mags))
    peak_to_peak = float(np.max(mags) - np.min(mags)) if len(mags) > 0 else 0.0
    jerk_vec = np.diff(mags) / dt_sec
    jerk_rms = float(np.sqrt(np.mean(jerk_vec**2))) if len(jerk_vec) > 0 else 0.0
    return {
        "motion_rms": rms,
        "variance": variance,
        "jerk": jerk_rms,
        "peak_to_peak": peak_to_peak,
        "var_x": float(np.var(dyn_x)),
        "var_y": float(np.var(dyn_y)),
        "var_z": float(np.var(dyn_z)),
        "feature_contract": CENTERED_AXIS_SYNTHETIC_V1,
    }


def _resolve_supervised_label(window: dict[str, Any]) -> str:
    """Returns a supervised ML label only when explicitly supplied; never guesses from RMS/predictions."""
    for key in ("motion_class_label", "ground_truth_label", "label"):
        candidate = window.get(key)
        if isinstance(candidate, str) and candidate in MOTION_CLASS_LABELS:
            return candidate
    return UNLABELED


class FeatureExtractor:
    """Computes physical motion features and multimodal representations from sensor windows."""

    @staticmethod
    def extract_window_features(
        window: dict[str, Any],
        dt_sec: float = 0.05,
        feature_contract: str = ANDROID_RUNTIME_FEATURES_V1,
    ) -> dict[str, Any]:
        raw_x = window.get("raw_accel_x", window["accel_x"])
        raw_y = window.get("raw_accel_y", window["accel_y"])
        raw_z = window.get("raw_accel_z", window["accel_z"])
        raw_elapsed = window.get("raw_elapsed_nanos")
        raw_acc = window.get("raw_accuracies")

        if feature_contract == ANDROID_RUNTIME_FEATURES_V1:
            metrics = extract_android_runtime_features_v1(
                xs=raw_x,
                ys=raw_y,
                zs=raw_z,
                elapsed_nanos=raw_elapsed,
                accuracies=raw_acc,
                dt_sec=dt_sec,
            )
            x_arr = np.asarray(raw_x, dtype=np.float32)
            y_arr = np.asarray(raw_y, dtype=np.float32)
            z_arr = np.asarray(raw_z, dtype=np.float32)
            var_x = float(np.var(x_arr - np.mean(x_arr)))
            var_y = float(np.var(y_arr - np.mean(y_arr)))
            var_z = float(np.var(z_arr - np.mean(z_arr)))
        elif feature_contract == CENTERED_AXIS_SYNTHETIC_V1:
            metrics = extract_centered_axis_synthetic_v1(
                xs=window["accel_x"],
                ys=window["accel_y"],
                zs=window["accel_z"],
                dt_sec=dt_sec,
            )
            var_x, var_y, var_z = metrics["var_x"], metrics["var_y"], metrics["var_z"]
        else:
            raise ValueError(
                f"Unknown feature_contract {feature_contract!r}; expected "
                f"{ANDROID_RUNTIME_FEATURES_V1!r} or {CENTERED_AXIS_SYNTHETIC_V1!r}"
            )

        raw_sample_count = int(window.get("raw_sample_count", window.get("sample_count", len(raw_x))))
        model_tensor_count = int(window.get("model_tensor_sample_count", len(window["accel_x"])))
        coverage_fraction = float(
            window["coverage_fraction"]
            if "coverage_fraction" in window and window["coverage_fraction"] is not None
            else metrics.get("coverage_fraction", min(1.0, raw_sample_count / 20.0))
        )
        has_large_gap = bool(window.get("has_large_gap", False))
        max_gap_ms = int(window.get("max_gap_ms", round(dt_sec * 1000)))
        was_resampled = bool(window.get("was_resampled", raw_sample_count != model_tensor_count))

        label = _resolve_supervised_label(window)
        signal_eligible = bool(
            window.get("signal_eligible", (coverage_fraction >= 0.85) and (not has_large_gap))
        )
        training_eligible = bool(signal_eligible and (label in MOTION_CLASS_LABELS))

        flags = [f for f in str(window.get("quality_flags", "")).split(",") if f]
        if label not in MOTION_CLASS_LABELS and "UNLABELED_WINDOW" not in flags:
            flags.append("UNLABELED_WINDOW")

        return {
            "window_id": window["window_id"],
            "session_id": window["session_id"],
            "start_utc_millis": int(window["start_utc_millis"]),
            "end_utc_millis": int(window["end_utc_millis"]),
            "prediction_cutoff_utc": int(window.get("prediction_cutoff_utc", window["end_utc_millis"])),
            "sample_count": raw_sample_count,
            "raw_sample_count": raw_sample_count,
            "model_tensor_sample_count": model_tensor_count,
            "coverage_fraction": coverage_fraction,
            "motion_rms": float(metrics["motion_rms"]),
            "variance": float(metrics["variance"]),
            "jerk": float(metrics["jerk"]),
            "peak_to_peak": float(metrics["peak_to_peak"]),
            "var_x": var_x,
            "var_y": var_y,
            "var_z": var_z,
            "mean_hr": window.get("mean_hr"),
            "hr_available": bool(window.get("hr_available", False)),
            "hr_source": str(window.get("hr_source", "NONE" if not window.get("hr_available") else "BLE_REAL")),
            "label": label,
            "ground_truth_activity": window.get("ground_truth_activity"),
            "predicted_motion_class": window.get("predicted_motion_class"),
            "provenance": str(window.get("provenance", "PHONE_REAL")),
            "has_large_gap": has_large_gap,
            "max_gap_ms": max_gap_ms,
            "was_resampled": was_resampled,
            "training_eligible": training_eligible,
            "quality_flags": ",".join(sorted(set(flags))),
            "feature_contract": feature_contract,
        }

    @staticmethod
    def extract_all_windows(
        windows: list[dict[str, Any]],
        feature_contract: str = ANDROID_RUNTIME_FEATURES_V1,
    ) -> pl.DataFrame:
        """Batch feature extraction returning a Polars DataFrame."""
        if not windows:
            return pl.DataFrame()
        rows = [FeatureExtractor.extract_window_features(w, feature_contract=feature_contract) for w in windows]
        return pl.DataFrame(rows)
