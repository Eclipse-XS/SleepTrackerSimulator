"""Tests for Storage Safety, Overwrite Prevention, Path Containment, and Failure Recovery."""

from __future__ import annotations

import os
from pathlib import Path
import sqlite3
import pytest

from ml.data.sqlite_storage import (
    APPROVED_SYNTHETIC_DIR,
    OutputPolicy,
    SqliteStorageManager,
)
from ml.simulation.generator import GeneratorConfig, SyntheticDatasetGenerator


def test_default_output_policy_is_fail_if_exists(tmp_path: Path):
    """SqliteStorageManager must default to FAIL_IF_EXISTS."""
    db_path = tmp_path / "test_default.sqlite"
    sm = SqliteStorageManager(db_path=db_path)
    assert sm.output_policy == OutputPolicy.FAIL_IF_EXISTS


def test_generator_refuses_to_overwrite_existing_db_by_default(tmp_path: Path):
    """When target DB exists and policy is FAIL_IF_EXISTS, generate_dataset must raise FileExistsError."""
    db_path = tmp_path / "existing.sqlite"
    # Create existing dummy database
    sm = SqliteStorageManager(db_path=db_path, output_policy=OutputPolicy.REPLACE_GENERATED_DATA)
    sm.init_schema(recreate=True, force_replace=True)
    assert db_path.exists()

    # Now attempt generation with default FAIL_IF_EXISTS
    sm_strict = SqliteStorageManager(db_path=db_path, output_policy=OutputPolicy.FAIL_IF_EXISTS)
    gen = SyntheticDatasetGenerator(storage_manager=sm_strict)

    cfg = GeneratorConfig(
        profile_name="TEST_MINI",
        subject_count=1,
        sessions_per_subject=1,
        session_duration_min=0.1,
        master_seed=42,
    )

    with pytest.raises(FileExistsError, match="Target database already exists"):
        gen.generate_dataset(config=cfg, output_policy=OutputPolicy.FAIL_IF_EXISTS)


def test_path_escape_rejection():
    """Attempting to initialize database in an unauthorized directory (e.g. app/src/main/assets) must fail."""
    illegal_path = Path("C:/unauthorized/system32/db.sqlite")
    sm = SqliteStorageManager(db_path=illegal_path)
    assert not sm._is_safe_path()

    with pytest.raises(PermissionError, match="outside approved synthetic directory"):
        sm.init_schema(recreate=True)


def test_clean_recovery_after_injected_generation_failure(tmp_path: Path, monkeypatch):
    """If generation fails halfway through, staging DB is deleted and original DB remains untouched."""
    db_path = tmp_path / "production_intact.sqlite"

    # Step 1: Create an original valid database with a known marker
    sm_init = SqliteStorageManager(db_path=db_path, output_policy=OutputPolicy.REPLACE_GENERATED_DATA)
    sm_init.init_schema(recreate=True, force_replace=True)
    with sm_init.session_scope() as conn:
        conn.execute("""
            INSERT INTO subjects (subject_id, baseline_hr, hr_response_amplitude, hr_tau_rise_sec,
                hr_tau_recovery_sec, hr_variability_std, movement_scale, restlessness_tendency,
                preferred_placement, device_orientation_euler, sensor_noise_std, ble_packet_loss_rate,
                typical_duration_min, generator_version, created_at_utc_ms)
            VALUES ('ORIGINAL-SUBJ', 60.0, 15.0, 10.0, 30.0, 2.0, 1.0, 0.2, 'CHEST', '[]', 0.05, 0.01, 30.0, '2.1.0', 1000);
        """)

    # Verify original subject exists
    with sm_init.session_scope() as conn:
        count = conn.execute("SELECT COUNT(*) FROM subjects WHERE subject_id = 'ORIGINAL-SUBJ';").fetchone()[0]
        assert count == 1

    # Step 2: Inject a failure into the generator during session creation
    gen = SyntheticDatasetGenerator(storage_manager=sm_init)
    cfg = GeneratorConfig(
        profile_name="FAIL_INJECT",
        subject_count=1,
        sessions_per_subject=1,
        session_duration_min=0.1,
        master_seed=99,
        fault_scenario="heavy_dropout",
    )

    def failing_inject_faults(*args, **kwargs):
        raise RuntimeError("Simulated transient hardware simulation crash!")

    from ml.simulation import failure_simulator
    monkeypatch.setattr(failure_simulator.FailureSimulator, "inject_faults", failing_inject_faults)

    with pytest.raises(RuntimeError, match="Simulated transient hardware simulation crash!"):
        gen.generate_dataset(config=cfg, output_policy=OutputPolicy.REPLACE_GENERATED_DATA)

    # Step 3: Verify staging file was cleaned up
    pid = os.getpid()
    staging_file = db_path.with_name(f"{db_path.name}.staging.{pid}")
    assert not staging_file.exists(), f"Staging file was not cleaned up: {staging_file}"

    # Step 4: Verify original database was preserved and is completely intact
    assert db_path.exists()
    with sm_init.session_scope() as conn:
        count = conn.execute("SELECT COUNT(*) FROM subjects WHERE subject_id = 'ORIGINAL-SUBJ';").fetchone()[0]
        assert count == 1, "Original database was corrupted or overwritten by failed generation!"
