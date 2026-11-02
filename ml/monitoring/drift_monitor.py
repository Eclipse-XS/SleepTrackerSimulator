"""Data and Model Monitoring Engine for SleepSense 2.0.

Computes:
1. Data Quality Telemetry (sample counts, coverage, dropouts, missingness)
2. Model Inference Behavior (prediction distributions, confidence, latency)
3. Population Stability Index (PSI) & feature drift between standard and shifted cohorts.
"""

from __future__ import annotations

import sqlite3
import sys
from pathlib import Path
from typing import Any

import numpy as np
import polars as pl

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

STANDARD_DB = ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"
SHIFTED_DB = ROOT / "data" / "synthetic" / "sleepsense_shifted.sqlite"


def calculate_psi(expected: np.ndarray, actual: np.ndarray, num_bins: int = 10) -> float:
    """Calculates the Population Stability Index (PSI) between two feature distributions.

    PSI Interpretation:
    - PSI < 0.10: No significant distribution change.
    - 0.10 <= PSI < 0.25: Moderate shift / moderate drift.
    - PSI >= 0.25: Significant distributional drift requiring model recalibration.
    """
    if len(expected) == 0 or len(actual) == 0:
        return 0.0

    # Determine quantile bin edges from expected (baseline) distribution
    percentiles = np.linspace(0.0, 100.0, num_bins + 1)
    bin_edges = np.percentile(expected, percentiles)
    bin_edges[0] -= 1e-5
    bin_edges[-1] += 1e-5

    # Remove non-unique bin edges if data is constant
    unique_edges = np.unique(bin_edges)
    if len(unique_edges) < 2:
        return 0.0

    expected_counts, _ = np.histogram(expected, bins=unique_edges)
    actual_counts, _ = np.histogram(actual, bins=unique_edges)

    # Add smoothing epsilon to avoid division by zero
    eps = 1e-4
    expected_pct = (expected_counts + eps) / (len(expected) + eps * len(expected_counts))
    actual_pct = (actual_counts + eps) / (len(actual) + eps * len(actual_counts))

    psi_value = np.sum((actual_pct - expected_pct) * np.log(actual_pct / expected_pct))
    return float(psi_value)


class DriftMonitor:
    """Computes end-to-end monitoring metrics, quality telemetry, and PSI drift."""

    def __init__(self, standard_db: Path | str = STANDARD_DB, shifted_db: Path | str = SHIFTED_DB):
        self.standard_db = Path(standard_db)
        self.shifted_db = Path(shifted_db)

    def compute_monitoring_report(self) -> dict[str, Any]:
        """Generates comprehensive monitoring metrics across data quality, inference, and drift."""
        conn_std = sqlite3.connect(str(self.standard_db))
        conn_shift = sqlite3.connect(str(self.shifted_db)) if self.shifted_db.exists() else None

        report: dict[str, Any] = {}

        # 1. Data Quality Telemetry
        quality_query = """
            SELECT
                COUNT(*) AS total_sessions,
                SUM(duration_sec) / 3600.0 AS total_hours,
                SUM(CASE WHEN quality_status = 'EXCELLENT' THEN 1 ELSE 0 END) AS excellent_sessions,
                SUM(CASE WHEN quality_status = 'DEGRADED' THEN 1 ELSE 0 END) AS degraded_sessions
            FROM sessions;
        """
        sess_stats = conn_std.execute(quality_query).fetchone()

        accel_count = conn_std.execute("SELECT COUNT(*) FROM accelerometer_samples;").fetchone()[0]
        hr_count = conn_std.execute("SELECT COUNT(*) FROM heart_rate_samples;").fetchone()[0]
        window_count = conn_std.execute("SELECT COUNT(*) FROM feature_windows;").fetchone()[0]
        missing_hr_windows = conn_std.execute("SELECT COUNT(*) FROM feature_windows WHERE hr_available = 0;").fetchone()[0]

        event_counts = conn_std.execute(
            "SELECT event_type, COUNT(*) FROM data_quality_events GROUP BY event_type;"
        ).fetchall()

        report["data_quality"] = {
            "total_sessions": sess_stats[0],
            "total_observation_hours": round(sess_stats[1], 2),
            "excellent_sessions": sess_stats[2],
            "degraded_sessions": sess_stats[3],
            "accelerometer_samples": accel_count,
            "heart_rate_samples": hr_count,
            "feature_windows": window_count,
            "missing_hr_windows": missing_hr_windows,
            "missing_hr_percentage": round(100.0 * missing_hr_windows / max(1, window_count), 2),
            "quality_events_by_type": {e[0]: e[1] for e in event_counts},
        }

        # 2. Inference Behavior
        preds_summary = conn_std.execute(
            """
            SELECT model_name, predicted_class, COUNT(*), ROUND(AVG(confidence), 4), ROUND(AVG(inference_duration_us), 1)
            FROM model_predictions
            GROUP BY model_name, predicted_class
            ORDER BY model_name, predicted_class;
            """
        ).fetchall()

        models_inf: dict[str, Any] = {}
        for m_name, pred_cls, cnt, conf, lat in preds_summary:
            if m_name not in models_inf:
                models_inf[m_name] = {"predictions": {}, "latencies_us": []}
            models_inf[m_name]["predictions"][pred_cls] = cnt
            models_inf[m_name]["mean_confidence"] = conf
            models_inf[m_name]["mean_latency_us"] = lat

        report["inference_behavior"] = models_inf

        # 3. Population Stability Index (PSI) Drift Analysis
        if conn_shift:
            df_std = pl.read_database("SELECT motion_rms, variance, jerk, peak_to_peak, mean_hr FROM feature_windows;", conn_std)
            df_shift = pl.read_database("SELECT motion_rms, variance, jerk, peak_to_peak, mean_hr FROM feature_windows;", conn_shift)

            features_to_monitor = ["motion_rms", "variance", "jerk", "peak_to_peak"]
            psi_results: dict[str, Any] = {}

            for f in features_to_monitor:
                exp_vals = df_std[f].drop_nulls().to_numpy()
                act_vals = df_shift[f].drop_nulls().to_numpy()
                psi_val = calculate_psi(exp_vals, act_vals, num_bins=10)

                shift_label = (
                    "STABLE (No significant drift)" if psi_val < 0.10 else
                    "MODERATE_DRIFT" if psi_val < 0.25 else
                    "SEVERE_DRIFT (Recalibration Required)"
                )

                psi_results[f] = {
                    "psi": round(psi_val, 4),
                    "baseline_mean": round(float(np.mean(exp_vals)), 4),
                    "shifted_mean": round(float(np.mean(act_vals)), 4),
                    "baseline_std": round(float(np.std(exp_vals)), 4),
                    "shifted_std": round(float(np.std(act_vals)), 4),
                    "drift_status": shift_label,
                }

            # HR PSI
            hr_exp = df_std["mean_hr"].drop_nulls().to_numpy()
            hr_act = df_shift["mean_hr"].drop_nulls().to_numpy()
            if len(hr_exp) > 0 and len(hr_act) > 0:
                psi_hr = calculate_psi(hr_exp, hr_act, num_bins=10)
                psi_results["mean_hr"] = {
                    "psi": round(psi_hr, 4),
                    "baseline_mean": round(float(np.mean(hr_exp)), 1),
                    "shifted_mean": round(float(np.mean(hr_act)), 1),
                    "baseline_std": round(float(np.std(hr_exp)), 1),
                    "shifted_std": round(float(np.std(hr_act)), 1),
                    "drift_status": "MODERATE_DRIFT" if psi_hr < 0.25 else "SEVERE_DRIFT",
                }

            report["feature_drift_psi"] = psi_results

        conn_std.close()
        if conn_shift:
            conn_shift.close()

        return report


if __name__ == "__main__":
    monitor = DriftMonitor()
    rep = monitor.compute_monitoring_report()
    print("Monitoring Report Generated!")
    print(rep)
