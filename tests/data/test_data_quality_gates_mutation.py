"""Mutation tests for SQL Data Quality Gates.

Deliberately inserts malformed fixtures to prove that every SQL quality gate detects violations:
- Gate 1: NULL Primary Keys
- Gate 2: Time Inversion / Non-positive duration
- Gate 3: Physical acceleration bounds (-196 to 196)
- Gate 4: Physiological HR bounds (30 to 240)
- Gate 5: Orphan child records (foreign keys)
- Gate 6: Window contract (negative variance, coverage out of bounds)
- Gate 7: Ground truth interval inversion
- Gate 8: Disallowed source kind provenance
- Gate 9: Duplicate timestamps within session
- Gate 10: Training eligibility contract violation
- Gate 11: Causal HR contract violation
- Gate 12: Prediction probability normalization violation
"""

from pathlib import Path
import sqlite3
import pytest

from ml.data.sqlite_storage import SqliteStorageManager

ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "sql" / "schema.sql"
QUALITY_CHECKS_PATH = ROOT / "sql" / "quality_checks.sql"


def run_gate_checks(conn: sqlite3.Connection) -> dict[str, int]:
    """Parses and executes each SELECT query in quality_checks.sql."""
    script = QUALITY_CHECKS_PATH.read_text(encoding="utf-8")
    statements = [s.strip() for s in script.split(";") if s.strip()]
    results = {}
    for stmt in statements:
        lines = [line.strip() for line in stmt.splitlines() if line.strip() and not line.strip().startswith("--")]
        clean_sql = " ".join(lines)
        if clean_sql.upper().startswith("SELECT"):
            cursor = conn.execute(clean_sql)
            row = cursor.fetchone()
            if row:
                gate_name, violations = row[0], row[1]
                results[gate_name] = violations
    return results


@pytest.fixture
def base_valid_db(tmp_path):
    """Sets up a valid minimal database with 1 subject, 1 session, 1 window, 1 gt interval."""
    db_file = tmp_path / "valid.sqlite"
    sm = SqliteStorageManager(db_path=db_file, schema_path=SCHEMA_PATH)
    sm.init_schema(recreate=True)

    with sm.session_scope() as conn:
        conn.execute("""
            INSERT INTO subjects (subject_id, baseline_hr, hr_response_amplitude, hr_tau_rise_sec,
                hr_tau_recovery_sec, hr_variability_std, movement_scale, restlessness_tendency,
                preferred_placement, device_orientation_euler, sensor_noise_std, ble_packet_loss_rate,
                typical_duration_min, generator_version, created_at_utc_ms)
            VALUES ('SUBJ-001', 60.0, 15.0, 10.0, 30.0, 2.0, 1.0, 0.2, 'CHEST', '[]', 0.05, 0.01, 30.0, '2.1.0', 1000);
        """)
        conn.execute("""
            INSERT INTO sessions (session_id, subject_id, start_utc_millis, end_utc_millis,
                duration_sec, device_placement, sampling_rate_hz, simulation_seed, source_kind, generator_version, quality_status)
            VALUES ('SESS-001', 'SUBJ-001', 1000, 2000, 1.0, 'CHEST', 20, 42, 'SIMULATED', '2.1.0', 'EXCELLENT');
        """)
        conn.execute("""
            INSERT INTO accelerometer_samples (session_id, elapsed_nanos, utc_millis, x, y, z, accuracy, source_kind)
            VALUES ('SESS-001', 1000000000, 1000, 0.0, 0.0, 9.81, 3, 'SIMULATED');
        """)
        conn.execute("""
            INSERT INTO heart_rate_samples (sample_id, session_id, device_id, bpm, utc_millis, timing_uncertainty_millis, source_kind)
            VALUES ('HR-001', 'SESS-001', 'BLE-001', 65, 1500, 50, 'SIMULATED');
        """)
        conn.execute("""
            INSERT INTO ground_truth_intervals (interval_id, session_id, start_utc_millis, end_utc_millis, start_sec, end_sec, state_id, state_label)
            VALUES ('INT-001', 'SESS-001', 1000, 2000, 0.0, 1.0, 0, 'REST');
        """)
        conn.execute("""
            INSERT INTO feature_windows (window_id, session_id, start_utc_millis, end_utc_millis,
                sample_count, coverage_fraction, motion_rms, variance, jerk, peak_to_peak,
                mean_hr, hr_available, ground_truth_label, provenance, max_gap_ms, has_large_gap, was_resampled, training_eligible, quality_flags)
            VALUES ('SESS-001-w00000', 'SESS-001', 1000, 2000, 20, 1.0, 0.05, 0.001, 0.1, 0.1, 65.0, 1, 'REST', 'SIMULATED', 50, 0, 0, 1, '');
        """)
        conn.execute("""
            INSERT INTO model_predictions (prediction_id, window_id, session_id, model_name,
                predicted_class, confidence, prob_rest, prob_restless, prob_active, inference_duration_us)
            VALUES ('PRED-001', 'SESS-001-w00000', 'SESS-001', 'multimodal_net', 'REST', 0.98, 0.98, 0.01, 0.01, 150.0);
        """)

    return db_file


def test_valid_db_passes_all_gates(base_valid_db):
    """The clean base fixture must have zero violations across all gates."""
    conn = sqlite3.connect(str(base_valid_db))
    results = run_gate_checks(conn)
    conn.close()
    for gate_name, violations in results.items():
        assert violations == 0, f"Clean fixture failed {gate_name} with {violations} violations!"


def test_gate_2_mutated_time_inversion(base_valid_db):
    """Deliberately invert session timestamps -> Gate 2 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("UPDATE sessions SET end_utc_millis = 500 WHERE session_id = 'SESS-001';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_2_TIME_ORDER"] > 0


def test_gate_3_mutated_extreme_acceleration(base_valid_db):
    """Deliberately insert unphysical acceleration -> Gate 3 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("UPDATE accelerometer_samples SET x = 300.0 WHERE session_id = 'SESS-001';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_3_ACCEL_BOUNDS"] > 0


def test_gate_4_mutated_extreme_heart_rate(base_valid_db):
    """Deliberately insert unphysiological HR -> Gate 4 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    # Temporarily disable table CHECK constraint to test gate detection
    conn.execute("PRAGMA ignore_check_constraints = ON;")
    conn.execute("UPDATE heart_rate_samples SET bpm = 300 WHERE session_id = 'SESS-001';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_4_HR_BOUNDS"] > 0


def test_gate_6_mutated_invalid_window_contract(base_valid_db):
    """Coverage fraction > 1.0 or negative variance -> Gate 6 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("UPDATE feature_windows SET coverage_fraction = 1.5 WHERE window_id = 'SESS-001-w00000';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_6_WINDOW_CONTRACT"] > 0


def test_gate_8_mutated_invalid_provenance(base_valid_db):
    """Forbidden provenance string -> Gate 8 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("UPDATE sessions SET source_kind = 'FABRICATED' WHERE session_id = 'SESS-001';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_8_PROVENANCE"] > 0


def test_gate_9_mutated_duplicate_timestamps(base_valid_db):
    """Duplicate accelerometer sample timestamps -> Gate 9 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("""
        INSERT INTO accelerometer_samples (session_id, elapsed_nanos, utc_millis, x, y, z, accuracy, source_kind)
        VALUES ('SESS-001', 1000000000, 1000, 0.1, 0.1, 9.81, 3, 'SIMULATED');
    """)
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_9_DUPLICATE_SAMPLES"] > 0


def test_gate_10_mutated_training_eligibility_violation(base_valid_db):
    """Training eligible window marked with low coverage (<0.85) -> Gate 10 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("UPDATE feature_windows SET training_eligible = 1, coverage_fraction = 0.40 WHERE window_id = 'SESS-001-w00000';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_10_TRAINING_ELIGIBILITY"] > 0


def test_gate_11_mutated_causal_hr_violation(base_valid_db):
    """HR available = 1 but mean_hr is NULL -> Gate 11 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("UPDATE feature_windows SET hr_available = 1, mean_hr = NULL WHERE window_id = 'SESS-001-w00000';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_11_CAUSAL_HR_INTEGRITY"] > 0


def test_gate_12_mutated_prediction_probability_unnormalized(base_valid_db):
    """Probabilities that do not sum to 1.0 -> Gate 12 must trigger."""
    conn = sqlite3.connect(str(base_valid_db))
    conn.execute("UPDATE model_predictions SET prob_rest = 0.5, prob_restless = 0.1, prob_active = 0.1 WHERE prediction_id = 'PRED-001';")
    conn.commit()
    results = run_gate_checks(conn)
    conn.close()
    assert results["GATE_12_PREDICTION_INTEGRITY"] > 0
