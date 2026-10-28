"""Feature extraction engine for classical and deep learning models."""

from __future__ import annotations

import numpy as np
import polars as pl


class FeatureExtractor:
    """Computes physical motion features and multimodal representations from sensor windows."""

    @staticmethod
    def extract_window_features(window: dict[str, any], dt_sec: float = 0.05) -> dict[str, any]:
        xs = np.array(window["accel_x"], dtype=np.float32)
        ys = np.array(window["accel_y"], dtype=np.float32)
        zs = np.array(window["accel_z"], dtype=np.float32)

        # Remove gravity by subtracting channel means
        dyn_x = xs - np.mean(xs)
        dyn_y = ys - np.mean(ys)
        dyn_z = zs - np.mean(zs)

        mags = np.sqrt(dyn_x**2 + dyn_y**2 + dyn_z**2)
        rms = float(np.sqrt(np.mean(mags**2)))
        variance = float(np.var(mags))
        peak_to_peak = float(np.max(mags) - np.min(mags))

        # Jerk
        jerk = np.diff(mags) / dt_sec
        jerk_rms = float(np.sqrt(np.mean(jerk**2))) if len(jerk) > 0 else 0.0

        # Axes variances
        var_x = float(np.var(dyn_x))
        var_y = float(np.var(dyn_y))
        var_z = float(np.var(dyn_z))

        # Heuristic ground truth assignment if unlabelled
        label = window.get("label")
        if not label or label == "UNCERTAIN":
            if rms < 0.10 and variance < 0.015:
                label = "REST"
            elif rms < 0.85:
                label = "RESTLESS"
            else:
                label = "ACTIVE"

        mean_hr = window.get("mean_hr")
        hr_available = window.get("hr_available", False)

        return {
            "window_id": window["window_id"],
            "session_id": window["session_id"],
            "start_utc_millis": window["start_utc_millis"],
            "end_utc_millis": window["end_utc_millis"],
            "sample_count": len(xs),
            "coverage_fraction": 1.0,
            "motion_rms": rms,
            "variance": variance,
            "jerk": jerk_rms,
            "peak_to_peak": peak_to_peak,
            "var_x": var_x,
            "var_y": var_y,
            "var_z": var_z,
            "mean_hr": mean_hr,
            "hr_available": hr_available,
            "label": label,
            "provenance": window.get("provenance", "PHONE_REAL"),
        }

    @staticmethod
    def extract_all_windows(windows: list[dict[str, any]]) -> pl.DataFrame:
        """Batch feature extraction returning a validated Polars DataFrame."""
        rows = [FeatureExtractor.extract_window_features(w) for w in windows]
        return pl.DataFrame(rows)
