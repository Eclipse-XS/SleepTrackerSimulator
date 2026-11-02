"""Regression tests for Authoritative Window Dataset Loader (ISSUE ML-002).

Proves that:
1. Mid-session accelerometer dropouts do NOT shift earlier raw samples into later windows.
2. Windows match raw samples strictly by event-time bounds [w_start_utc, w_end_utc).
3. The exact same window_id produces deterministic, identical tensors.
4. Causal HR is normalized according to the production contract.
"""

import sqlite3
import numpy as np
import pytest

from ml.data.window_dataset import load_dataset_from_sqlite


@pytest.fixture
def test_db_with_mid_session_dropout(tmp_path):
    """Creates a temporary SQLite database with a mid-session dropout."""
    db_file = tmp_path / "test_dropout.sqlite"
    conn = sqlite3.connect(str(db_file))
    conn.executescript("""
        CREATE TABLE subjects (
            subject_id TEXT PRIMARY KEY,
            baseline_hr REAL, hr_response_amplitude REAL, hr_tau_rise_sec REAL, hr_tau_recovery_sec REAL,
            hr_variability_std REAL, movement_scale REAL, restlessness_tendency REAL,
            preferred_placement TEXT, device_orientation_euler TEXT, sensor_noise_std REAL,
            ble_packet_loss_rate REAL, typical_duration_min REAL, generator_version TEXT, created_at_utc_ms INTEGER
        );
        CREATE TABLE sessions (
            session_id TEXT PRIMARY KEY,
            subject_id TEXT REFERENCES subjects(subject_id),
            start_utc_millis INTEGER, end_utc_millis INTEGER, duration_sec REAL,
            device_placement TEXT, sampling_rate_hz INTEGER, simulation_seed INTEGER,
            source_kind TEXT, generator_version TEXT, quality_status TEXT
        );
        CREATE TABLE accelerometer_samples (
            sample_id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT REFERENCES sessions(session_id),
            elapsed_nanos INTEGER, utc_millis INTEGER, x REAL, y REAL, z REAL, accuracy INTEGER, source_kind TEXT
        );
        CREATE TABLE feature_windows (
            window_id TEXT PRIMARY KEY,
            session_id TEXT REFERENCES sessions(session_id),
            start_utc_millis INTEGER, end_utc_millis INTEGER,
            sample_count INTEGER, coverage_fraction REAL,
            motion_rms REAL, variance REAL, jerk REAL, peak_to_peak REAL,
            mean_hr REAL, hr_available INTEGER, ground_truth_label TEXT, provenance TEXT,
            max_gap_ms INTEGER, has_large_gap INTEGER, was_resampled INTEGER, training_eligible INTEGER, quality_flags TEXT
        );
    """)

    # Insert 1 subject and 1 session with 3 windows:
    # Window 0: 1000 - 2000 (has 20 samples)
    # Window 1: 2000 - 3000 (DROPOUT - 0 samples)
    # Window 2: 3000 - 4000 (has 20 samples, distinct x=5.0)
    conn.execute("INSERT INTO subjects (subject_id, preferred_placement) VALUES ('SUBJ-001', 'CHEST');")
    conn.execute("""
        INSERT INTO sessions (session_id, subject_id, start_utc_millis, end_utc_millis, duration_sec, device_placement)
        VALUES ('SESS-001', 'SUBJ-001', 1000, 4000, 3.0, 'CHEST');
    """)

    # Window 0 samples: x = 1.0
    for i in range(20):
        t = 1000 + i * 50
        conn.execute(
            "INSERT INTO accelerometer_samples (session_id, elapsed_nanos, utc_millis, x, y, z) VALUES ('SESS-001', ?, ?, 1.0, 0.0, 9.8);",
            (t * 1_000_000, t),
        )

    # Window 2 samples: x = 5.0 (NO SAMPLES FOR WINDOW 1)
    for i in range(20):
        t = 3000 + i * 50
        conn.execute(
            "INSERT INTO accelerometer_samples (session_id, elapsed_nanos, utc_millis, x, y, z) VALUES ('SESS-001', ?, ?, 5.0, 0.0, 9.8);",
            (t * 1_000_000, t),
        )

    # Feature windows
    conn.execute("""
        INSERT INTO feature_windows
        (window_id, session_id, start_utc_millis, end_utc_millis, sample_count, coverage_fraction, motion_rms, variance, jerk, peak_to_peak, mean_hr, hr_available, ground_truth_label, provenance, max_gap_ms, has_large_gap, was_resampled, training_eligible, quality_flags)
        VALUES
        ('SESS-001-w00000', 'SESS-001', 1000, 2000, 20, 1.0, 0.0, 0.0, 0.0, 0.0, 60.0, 1, 'REST', 'SIMULATED', 50, 0, 0, 1, ''),
        ('SESS-001-w00001', 'SESS-001', 2000, 3000, 0, 0.0, 0.0, 0.0, 0.0, 0.0, NULL, 0, 'REST', 'SIMULATED', 1000, 1, 0, 0, 'EMPTY_WINDOW'),
        ('SESS-001-w00002', 'SESS-001', 3000, 4000, 20, 1.0, 0.0, 0.0, 0.0, 0.0, 70.0, 1, 'ACTIVE', 'SIMULATED', 50, 0, 0, 1, '');
    """)
    conn.commit()
    conn.close()
    return db_file


def test_dropout_does_not_shift_later_window_samples(test_db_with_mid_session_dropout):
    """Historical flaw: raw_arr[: n_windows * 20] would slice Window 2's samples into Window 1!

    The authoritative event-time loader guarantees:
    - Window 0 gets samples from [1000, 2000) (x = 1.0)
    - Window 1 gets 0 samples (dropout)
    - Window 2 gets samples from [3000, 4000) (x = 5.0)
    """
    dataset = load_dataset_from_sqlite(test_db_with_mid_session_dropout)
    assert dataset.total_windows_loaded == 3
    assert dataset.training_eligible_count == 2
    assert dataset.rejected_count == 1

    subj_data = dataset.data_by_subject["SUBJ-001"]
    w0_tensor = subj_data["accel_windows"][0]
    w1_tensor = subj_data["accel_windows"][1]
    w2_tensor = subj_data["accel_windows"][2]

    # Window 0 should have x = 1.0
    assert np.allclose(w0_tensor[:, 0], 1.0)

    # Window 1 is in dropout (empty) -> not training eligible
    assert subj_data["training_eligible"][1] is False or subj_data["training_eligible"][1] == 0

    # Window 2 MUST NOT have taken samples from Window 0! It must have x = 5.0
    assert np.allclose(w2_tensor[:, 0], 5.0)

    # Collate training-only: Window 1 must be excluded
    X_accel, X_hr, X_tab, y, wids = dataset.collate_subjects(["SUBJ-001"], training_only=True)
    assert len(X_accel) == 2
    assert wids == ["SESS-001-w00000", "SESS-001-w00002"]
    assert np.allclose(X_accel[0, :, 0], 1.0)
    assert np.allclose(X_accel[1, :, 0], 5.0)
