"""Comprehensive Unit Tests for Authoritative Event-Time Window Builder.

Validates:
- Event-time window boundaries [start_ms, end_ms)
- Accurate sub-bin coverage calculation (no constant 1.0)
- Gap detection, dropouts, jitter, reordered/duplicate timestamps
- Non-finite acceleration filtering
- Causal heart rate alignment and signed staleness gating
- Maximum interval overlap ground truth assignment
- Model-ready tensor shape [20, 3] and training eligibility
"""

import numpy as np
import pytest

from ml.data.window_builder import (
    WindowQualityPolicy,
    build_session_windows,
)


class DummyInterval:
    def __init__(self, start_sec: float, end_sec: float, state_name: str):
        self.start_sec = start_sec
        self.end_sec = end_sec
        self.state_name = state_name


def test_perfect_20_samples_window():
    session_start = 1_000_000
    session_end = session_start + 1_000
    accel = [
        {"utc_millis": session_start + i * 50, "x": 0.0, "y": 0.0, "z": 9.81}
        for i in range(20)
    ]
    hr = [{"utc_millis": session_start + 500, "bpm": 65}]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows(
        session_id="SESS-001",
        session_start_ms=session_start,
        session_end_ms=session_end,
        raw_accelerometer_samples=accel,
        raw_heart_rate_samples=hr,
        ground_truth_intervals=intervals,
    )

    assert len(res.windows) == 1
    w = res.windows[0]
    assert w["coverage_fraction"] == 1.0
    assert w["sample_count"] == 20
    assert w["has_large_gap"] == 0
    assert w["training_eligible"] == 1
    assert w["ground_truth_label"] == "REST"
    assert w["hr_available"] == 1
    assert w["mean_hr"] == 65.0
    assert res.model_tensors[0].shape == (20, 3)


def test_19_samples_with_acceptable_timing():
    session_start = 1_000_000
    session_end = session_start + 1_000
    # 19 samples spaced at ~52ms
    accel = [
        {"utc_millis": session_start + int(i * 52), "x": 0.1, "y": -0.1, "z": 9.8}
        for i in range(19)
    ]
    hr = [{"utc_millis": session_start + 900, "bpm": 70}]
    intervals = [DummyInterval(0.0, 1.0, "RESTLESS")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, hr, intervals)
    w = res.windows[0]
    assert w["coverage_fraction"] >= 0.90
    assert w["sample_count"] == 19
    assert w["training_eligible"] == 1
    assert res.model_tensors[0].shape == (20, 3)


def test_21_samples_with_jitter():
    session_start = 1_000_000
    session_end = session_start + 1_000
    accel = [
        {"utc_millis": session_start + int(i * 47), "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(21)
    ]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, [], intervals)
    w = res.windows[0]
    assert w["sample_count"] == 21
    assert w["coverage_fraction"] >= 0.95
    assert w["training_eligible"] == 1


def test_dropout_two_second_gap():
    session_start = 1_000_000
    session_end = session_start + 3_000  # 3 windows
    # Window 0 has samples, Window 1 is in a 2-second dropout (no samples), Window 2 has samples
    accel = [
        {"utc_millis": session_start + i * 50, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(20)
    ] + [
        {"utc_millis": session_start + 2000 + i * 50, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(20)
    ]
    intervals = [DummyInterval(0.0, 3.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, [], intervals)
    assert len(res.windows) == 3
    # Window 1: middle window in dropout
    w1 = res.windows[1]
    assert w1["sample_count"] == 0
    assert w1["coverage_fraction"] == 0.0
    assert w1["has_large_gap"] == 1
    assert w1["training_eligible"] == 0
    assert "EMPTY_WINDOW" in w1["quality_flags"]


def test_clustered_samples_large_gap_rejected():
    """20 samples clustered into first 100ms: sample_count=20 but large temporal gap -> low coverage & rejected."""
    session_start = 1_000_000
    session_end = session_start + 1_000
    accel = [
        {"utc_millis": session_start + i * 5, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(20)
    ]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, [], intervals)
    w = res.windows[0]
    assert w["sample_count"] == 20
    # Clustered in first 100ms -> coverage is low (around 0.10 - 0.15), max gap > 800ms
    assert w["coverage_fraction"] < 0.25
    assert w["has_large_gap"] == 1
    assert w["training_eligible"] == 0
    assert "LARGE_GAP" in w["quality_flags"]


def test_duplicate_and_reordered_timestamps():
    session_start = 1_000_000
    session_end = session_start + 1_000
    # Out of order timestamps and duplicates
    accel = [
        {"utc_millis": session_start + 100, "x": 1.0, "y": 1.0, "z": 9.8},
        {"utc_millis": session_start + 50, "x": 0.5, "y": 0.5, "z": 9.8},
        {"utc_millis": session_start + 100, "x": 1.0, "y": 1.0, "z": 9.8},  # duplicate
    ] + [
        {"utc_millis": session_start + i * 50, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(3, 20)
    ]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, [], intervals)
    w = res.windows[0]
    assert "DUPLICATE_SAMPLE_TIMESTAMP" in w["quality_flags"]


def test_non_finite_acceleration_filtered():
    session_start = 1_000_000
    session_end = session_start + 1_000
    accel = [
        {"utc_millis": session_start + i * 50, "x": np.nan, "y": 0.0, "z": 9.8}
        for i in range(20)
    ]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, [], intervals)
    w = res.windows[0]
    assert w["sample_count"] == 0
    assert w["training_eligible"] == 0


def test_ground_truth_majority_overlap():
    session_start = 1_000_000
    session_end = session_start + 1_000
    accel = [
        {"utc_millis": session_start + i * 50, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(20)
    ]
    # Interval 1: 0.0 to 0.7 (700ms) is ACTIVE
    # Interval 2: 0.7 to 1.0 (300ms) is REST
    intervals = [
        DummyInterval(0.0, 0.7, "ACTIVE"),
        DummyInterval(0.7, 1.0, "REST"),
    ]

    res = build_session_windows("SESS-001", session_start, session_end, accel, [], intervals)
    w = res.windows[0]
    # Majority of the window is in ACTIVE (700ms vs 300ms)
    assert w["ground_truth_label"] == "ACTIVE"


def test_causal_heart_rate_future_leakage_prevented():
    """HR measurement arriving after window cutoff (w_end_utc) MUST NOT be used."""
    session_start = 1_000_000
    session_end = session_start + 1_000  # window ends at 1_001_000
    accel = [
        {"utc_millis": session_start + i * 50, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(20)
    ]
    # HR arrives at 1_001_200 (200ms AFTER cutoff)
    hr = [{"utc_millis": session_start + 1200, "bpm": 85}]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, hr, intervals)
    w = res.windows[0]
    # HR in the future must NOT be picked up
    assert w["hr_available"] == 0
    assert w["mean_hr"] is None
    assert res.hr_feature_vectors[0] == [0.0, 0.0]


def test_causal_heart_rate_stale_rejected():
    """HR measurement older than 10 seconds is rejected as stale."""
    session_start = 1_000_000
    session_end = session_start + 1_000
    accel = [
        {"utc_millis": session_start + i * 50, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(20)
    ]
    # HR was 11 seconds prior to window end (age = 11,000ms)
    hr = [{"utc_millis": session_end - 11_000, "bpm": 60}]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, hr, intervals)
    w = res.windows[0]
    assert w["hr_available"] == 0
    assert "STALE_HEART_RATE" in w["quality_flags"]
    assert res.hr_feature_vectors[0] == [0.0, 0.0]


def test_causal_heart_rate_valid_fresh():
    """HR measurement at or within 10s before cutoff is valid and causally aligned."""
    session_start = 1_000_000
    session_end = session_start + 1_000
    accel = [
        {"utc_millis": session_start + i * 50, "x": 0.0, "y": 0.0, "z": 9.8}
        for i in range(20)
    ]
    # HR exactly at window cutoff
    hr = [{"utc_millis": session_end, "bpm": 70}]
    intervals = [DummyInterval(0.0, 1.0, "REST")]

    res = build_session_windows("SESS-001", session_start, session_end, accel, hr, intervals)
    w = res.windows[0]
    assert w["hr_available"] == 1
    assert w["mean_hr"] == 70.0
    # norm_bpm = (70 - 60) / 40 = 0.25
    assert res.hr_feature_vectors[0] == [0.25, 1.0]
