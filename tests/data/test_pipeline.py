"""Unit tests for SleepSense Python data engineering pipeline."""

from __future__ import annotations

import tempfile
from pathlib import Path

import polars as pl
import pytest

from pipelines.analytics.duckdb_analytics import SleepDataAnalytics
from pipelines.datasets.dataset_builder import DatasetBuilder
from pipelines.features.feature_extractor import FeatureExtractor
from pipelines.transformation.cleaning import DataCleaner
from pipelines.transformation.synchronizer import SensorSynchronizer


def test_clean_accelerometer_deduplication_and_null_filtering() -> None:
    raw_data = pl.DataFrame({
        "session_id": ["s1", "s1", "s1", "s1"],
        "source_id": ["accel", "accel", "accel", "accel"],
        "elapsed_nanos": [100, 100, 200, 300],  # 100 is duplicate
        "utc_millis": [1000, 1000, 1050, 1100],
        "x": [0.0, 0.0, float("nan"), 0.1],  # nan should be dropped
        "y": [0.0, 0.0, 0.2, 0.2],
        "z": [9.81, 9.81, 9.81, 9.81],
        "accuracy": [3, 3, 3, 3],
        "source_kind": ["PHONE_REAL", "PHONE_REAL", "PHONE_REAL", "PHONE_REAL"],
    })

    cleaned = DataCleaner.clean_accelerometer(raw_data)
    # Row 1 is duplicate of Row 0; Row 2 has NaN in x; remaining rows: Row 0 and Row 3 -> 2 rows
    assert len(cleaned) == 2
    assert cleaned["elapsed_nanos"].to_list() == [100, 300]


def test_clean_heart_rate_range_filtering() -> None:
    raw_hr = pl.DataFrame({
        "session_id": ["s1", "s1", "s1", "s1"],
        "device_id": ["band", "band", "band", "band"],
        "bpm": [20, 65, 250, 70],  # 20 and 250 out of range
        "utc_millis": [1000, 1050, 1100, 1150],
        "source_kind": ["BLE_REAL", "BLE_REAL", "BLE_REAL", "BLE_REAL"],
    })

    cleaned = DataCleaner.clean_heart_rate(raw_hr)
    assert len(cleaned) == 2
    assert cleaned["bpm"].to_list() == [65, 70]


def test_sensor_synchronizer_multimodal_alignment() -> None:
    # 40 accelerometer samples -> 2 windows of 20 samples
    accel = pl.DataFrame({
        "session_id": ["s1"] * 40,
        "source_id": ["accel"] * 40,
        "elapsed_nanos": [i * 50_000_000 for i in range(40)],
        "utc_millis": [1000 + i * 50 for i in range(40)],
        "x": [0.0] * 40,
        "y": [0.0] * 40,
        "z": [9.81] * 40,
        "accuracy": [3] * 40,
        "source_kind": ["PHONE_REAL"] * 40,
    })

    # HR sample matching window 1 only
    hr = pl.DataFrame({
        "session_id": ["s1"],
        "device_id": ["band"],
        "bpm": [62],
        "utc_millis": [1500],
        "source_kind": ["BLE_SIMULATED"],
    })

    windows = SensorSynchronizer.create_synchronized_windows(accel, hr, window_size=20)
    assert len(windows) == 2

    # Window 0: utc_millis [1000, 1950], matching HR at 1500
    assert windows[0]["hr_available"] is True
    assert windows[0]["mean_hr"] == 62.0

    # Both windows have 20 samples
    assert len(windows[0]["accel_x"]) == 20
    assert len(windows[1]["accel_x"]) == 20


def test_feature_extractor_physical_motion_metrics() -> None:
    # Window with static gravity: z = 9.81, x=0, y=0
    static_window = {
        "window_id": "w0",
        "session_id": "s1",
        "start_utc_millis": 1000,
        "end_utc_millis": 2000,
        "accel_x": [0.0] * 20,
        "accel_y": [0.0] * 20,
        "accel_z": [9.81] * 20,
        "mean_hr": 55.0,
        "hr_available": True,
        "provenance": "PHONE_REAL",
    }

    features = FeatureExtractor.extract_window_features(static_window)
    assert features["motion_rms"] == pytest.approx(0.0, abs=1e-4)
    assert features["variance"] == pytest.approx(0.0, abs=1e-4)
    assert features["jerk"] == pytest.approx(0.0, abs=1e-4)
    assert features["label"] == "REST"
    assert features["hr_available"] is True
    assert features["mean_hr"] == 55.0


def test_dataset_builder_and_duckdb_analytics() -> None:
    with tempfile.TemporaryDirectory() as tmp_dir:
        out_path = Path(tmp_dir) / "test_dataset"

        # Generate sample windows
        raw_windows = []
        for i in range(25):
            raw_windows.append({
                "window_id": f"w-{i}",
                "session_id": "session-test-01" if i < 15 else "session-test-02",
                "start_utc_millis": 1700000000000 + i * 1000,
                "end_utc_millis": 1700000000000 + (i + 1) * 1000,
                "sample_count": 20,
                "coverage_fraction": 1.0,
                "motion_rms": 0.05 if i % 2 == 0 else 0.45,
                "variance": 0.005,
                "jerk": 0.01,
                "mean_hr": 60.0 + (i % 5),
                "hr_available": True,
                "label": "REST" if i % 2 == 0 else "RESTLESS",
                "provenance": "PHONE_REAL",
            })

        features_df = pl.DataFrame(raw_windows)

        # Build Parquet dataset
        built_dir = DatasetBuilder.build_parquet_dataset(features_df, out_path)
        assert (built_dir / "dataset_metadata.json").exists()

        # Load Parquet dataset back
        loaded_df = DatasetBuilder.load_parquet_dataset(built_dir)
        assert len(loaded_df) == 25

        # Group-aware split
        train_df, test_df = DatasetBuilder.split_train_test_by_session(
            loaded_df, test_session_ratio=0.5, seed=42
        )
        assert len(train_df) > 0
        assert len(test_df) > 0
        # Ensure session disjointness (no data leakage)
        train_sessions = set(train_df["session_id"].unique().to_list())
        test_sessions = set(test_df["session_id"].unique().to_list())
        assert train_sessions.isdisjoint(test_sessions)

        # DuckDB analytics
        analytics = SleepDataAnalytics(built_dir)
        summaries = analytics.get_session_summaries()
        assert len(summaries) == 2

        class_dist = analytics.get_class_distributions()
        assert len(class_dist) > 0

        rest_scores = analytics.get_rest_quality_scores()
        assert len(rest_scores) == 2
