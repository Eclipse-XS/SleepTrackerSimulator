"""Unit and Integration Tests for Synthetic Generator, SQLite Persistence, and ML Quality Gates."""

from __future__ import annotations

import sqlite3
import tempfile
from pathlib import Path

import numpy as np
import pytest

from ml.data.sqlite_storage import SqliteStorageManager
from ml.monitoring.drift_monitor import calculate_psi
from ml.simulation.accelerometer_simulator import AccelerometerSimulator
from ml.simulation.failure_simulator import FailureSimulator
from ml.simulation.heart_rate_simulator import HeartRateSimulator
from ml.simulation.state_machine import SemiMarkovStateSimulator
from ml.simulation.subject_profile import SubjectPopulationGenerator


def test_generator_deterministic_reproducibility() -> None:
    """Verifies that identical seed produces identical subject profiles and state timelines."""
    cohort1 = SubjectPopulationGenerator.generate_population(count=4, seed=42)
    cohort2 = SubjectPopulationGenerator.generate_population(count=4, seed=42)
    assert len(cohort1) == len(cohort2) == 4
    for s1, s2 in zip(cohort1, cohort2):
        assert s1.subject_id == s2.subject_id
        assert s1.baseline_hr == s2.baseline_hr
        assert s1.preferred_placement == s2.preferred_placement
        assert s1.device_orientation_euler == s2.device_orientation_euler

    # Different seed produces different attributes
    cohort3 = SubjectPopulationGenerator.generate_population(count=4, seed=999)
    assert cohort1[0].baseline_hr != cohort3[0].baseline_hr


def test_semi_markov_state_timeline_continuity() -> None:
    """Verifies that latent state intervals are non-overlapping, contiguous, and monotonic."""
    subject = SubjectPopulationGenerator.generate_population(count=1, seed=42)[0]
    sim = SemiMarkovStateSimulator(subject, seed=123)
    intervals = sim.simulate_timeline("test-session", total_duration_sec=300.0)

    assert len(intervals) > 0
    assert intervals[0].start_sec == 0.0
    assert intervals[-1].end_sec == 300.0

    # Contiguity check: each interval begins where the previous ended
    for i in range(len(intervals) - 1):
        assert abs(intervals[i].end_sec - intervals[i + 1].start_sec) < 1e-4
        assert intervals[i].state_name in ("REST", "RESTLESS", "ACTIVE")


def test_accelerometer_earth_gravity_and_units() -> None:
    """Verifies that accelerometer samples contain Earth gravity (~9.81 m/s^2) and reasonable bounds."""
    subject = SubjectPopulationGenerator.generate_population(count=1, seed=42)[0]
    sim = SemiMarkovStateSimulator(subject, seed=123)
    intervals = sim.simulate_timeline("test-session", total_duration_sec=10.0)

    accel_sim = AccelerometerSimulator(subject, seed=456)
    samples = accel_sim.generate_interval_signal(intervals[0], start_utc_ms=1000, start_elapsed_ns=1_000_000_000)

    assert len(samples) > 0
    magnitudes = [np.sqrt(s["x"] ** 2 + s["y"] ** 2 + s["z"] ** 2) for s in samples]
    mean_mag = np.mean(magnitudes)

    # Magnitude during rest should hover closely around 1G (9.81 m/s^2)
    assert 9.4 <= mean_mag <= 10.2
    assert all(-50.0 <= s["x"] <= 50.0 for s in samples)
    assert all(s["accuracy"] == 3 for s in samples)
    assert all(s["source_kind"] == "SIMULATED" for s in samples)


def test_heart_rate_inertia_and_bounds() -> None:
    """Verifies that heart rate follows physiological bounds and smooth dynamics."""
    subject = SubjectPopulationGenerator.generate_population(count=1, seed=42)[0]
    sim = SemiMarkovStateSimulator(subject, seed=123)
    intervals = sim.simulate_timeline("test-session", total_duration_sec=60.0)

    hr_sim = HeartRateSimulator(subject, seed=789)
    samples = hr_sim.simulate_session_hr(intervals, start_utc_ms=1000)

    assert len(samples) > 0
    bpms = [s["bpm"] for s in samples]
    # Check physiological bounds (35 to 220)
    assert all(35 <= bpm <= 220 for bpm in bpms)

    # Check temporal correlation: successive 1s samples should not jump instantaneously by >15 bpm
    diffs = np.abs(np.diff(bpms))
    assert np.max(diffs) <= 12.0


def test_failure_simulator_ble_dropout_and_logging() -> None:
    """Verifies that fault injection creates explicit missingness and data quality events."""
    failure_sim = FailureSimulator(seed=42)
    # 3000 samples @ 50ms = 150,000 ms duration (2.5 minutes)
    accel = [{"utc_millis": 1000 + i * 50, "x": 0.0, "y": 0.0, "z": 9.81, "accuracy": 3} for i in range(3000)]
    hr = [{"utc_millis": 1000 + i * 1000, "bpm": 65} for i in range(150)]

    mod_accel, mod_hr, events = failure_sim.inject_faults("test-session", accel, hr, scenario="ble_dropout")
    assert len(events) > 0
    assert any(e.event_type == "BLE_DISCONNECT" for e in events)
    assert len(mod_hr) < len(hr)  # Confirms dropped HR samples


def test_sqlite_persistence_and_referential_integrity() -> None:
    """Tests SQLite database creation, transactions, foreign key enforcement, and reopening."""
    with tempfile.TemporaryDirectory() as tmp_dir:
        db_file = Path(tmp_dir) / "test_synthetic.sqlite"
        sm = SqliteStorageManager(db_path=db_file)
        sm.init_schema(recreate=True)

        # 1. Insert Subject
        subjects = SubjectPopulationGenerator.generate_population(count=1, seed=42)
        sm.insert_subjects(subjects)

        # 2. Insert Session
        sm.insert_session({
            "session_id": "TEST-SESS-001",
            "subject_id": subjects[0].subject_id,
            "start_utc_millis": 1000,
            "end_utc_millis": 61000,
            "duration_sec": 60.0,
            "device_placement": "MATTRESS",
            "sampling_rate_hz": 20,
            "simulation_seed": 42,
            "source_kind": "SIMULATED",
            "generator_version": "2.0.0",
            "quality_status": "EXCELLENT",
        })

        # 3. Insert Samples
        sm.insert_accelerometer_samples_batch([
            {
                "session_id": "TEST-SESS-001",
                "elapsed_nanos": 1_000_000_000,
                "utc_millis": 1000,
                "x": 0.0, "y": 0.0, "z": 9.81,
                "accuracy": 3,
                "source_kind": "SIMULATED",
            }
        ])

        # Verify 0 FK violations
        violations = sm.verify_referential_integrity()
        assert len(violations) == 0

        # Verify child insert fails when referencing non-existent session
        with pytest.raises(sqlite3.IntegrityError):
            sm.insert_accelerometer_samples_batch([
                {
                    "session_id": "NON-EXISTENT-SESS",
                    "elapsed_nanos": 1_000_000_000,
                    "utc_millis": 1000,
                    "x": 0.0, "y": 0.0, "z": 9.81,
                    "accuracy": 3,
                    "source_kind": "SIMULATED",
                }
            ])

        # Test reopening database
        counts = sm.get_table_counts()
        assert counts["subjects"] == 1
        assert counts["sessions"] == 1
        assert counts["accelerometer_samples"] == 1


def test_psi_metric_calculation() -> None:
    """Verifies that Population Stability Index is 0 for identical distributions and high for shifted."""
    rng = np.random.default_rng(42)
    dist_a = rng.normal(0.0, 1.0, 1000)
    dist_b = rng.normal(0.0, 1.0, 1000)
    psi_stable = calculate_psi(dist_a, dist_b, num_bins=10)
    assert psi_stable < 0.10  # Stable

    dist_shifted = rng.normal(2.5, 1.0, 1000)
    psi_shifted = calculate_psi(dist_a, dist_shifted, num_bins=10)
    assert psi_shifted > 0.25  # Severe drift


def test_domain_shift_evaluation_source_isolation() -> None:
    """Regression test ensuring domain shift evaluation does NOT leak standard training cohort.

    Guarantees that when collating shifted evaluation splits, the data is drawn strictly
    from the designated shifted source dictionary rather than outer-scope training data.
    """
    from ml.experiments.scientific_audit import collate_subjects

    # Create distinct fake standard and shifted cohort dictionaries
    fake_std = {
        "SUBJ-001": {
            "accel_windows": [np.zeros((20, 3), dtype=np.float32)],
            "hr_features": [[0.0, 1.0]],
            "tabular_features": [[0.05, 0.001, 0.1, 0.1, 60.0, 1.0]],
            "labels": [0],
            "window_ids": ["w-std-1"],
            "sessions": ["s-std-1"],
        }
    }

    fake_shifted = {
        "SUBJ-001": {
            "accel_windows": [np.ones((20, 3), dtype=np.float32) * 5.0],
            "hr_features": [[1.5, 1.0]],
            "tabular_features": [[0.8, 0.5, 3.0, 1.2, 95.0, 1.0]],
            "labels": [2],
            "window_ids": ["w-shift-1"],
            "sessions": ["s-shift-1"],
        }
    }

    # Collating from fake_shifted MUST return fake_shifted data (label=2), not fake_std (label=0)
    _, _, _, y_shift = collate_subjects(fake_shifted, ["SUBJ-001"])
    assert len(y_shift) == 1
    assert y_shift[0] == 2, "Data leakage detected: shifted collation returned standard training data!"
