"""Tests for Storage Safety and Deterministic Dataset Identity (DE-004 & DE-005)."""

from pathlib import Path
import pytest

from ml.data.sqlite_storage import OutputPolicy, SqliteStorageManager
from ml.simulation.generator import GeneratorConfig, SyntheticDatasetGenerator


def test_unsafe_path_recreation_rejected(tmp_path):
    """Attempting to recreate a database in an unauthorized production/user directory is blocked."""
    unsafe_path = Path("C:/Users/Eclipse/AndroidStudioProjects/SleepTrackerSimulator/app/databases/sleepsense_user.db")
    sm = SqliteStorageManager(db_path=unsafe_path)

    # Calling init_schema with recreate=True on unsafe path must raise PermissionError
    with pytest.raises(PermissionError) as exc_info:
        sm.init_schema(recreate=True, force_replace=True)

    assert "outside approved synthetic directory" in str(exc_info.value)


def test_fail_if_exists_policy_prevents_silent_overwrite(tmp_path):
    """OutputPolicy.FAIL_IF_EXISTS raises FileExistsError if database already exists."""
    db_file = tmp_path / "existing.sqlite"
    sm = SqliteStorageManager(db_path=db_file, output_policy=OutputPolicy.FAIL_IF_EXISTS)
    sm.init_schema(recreate=False)  # First creation succeeds

    # Second creation without recreate flag must fail
    with pytest.raises(FileExistsError):
        sm.init_schema(recreate=False)


def test_manifest_determinism_and_reproducible_fingerprint(tmp_path):
    """Two generation runs with identical configurations produce identical manifest fingerprints (DE-005)."""
    cfg = GeneratorConfig(
        profile_name="TEST_MINI",
        subject_count=2,
        sessions_per_subject=1,
        session_duration_min=0.5,
        master_seed=42,
    )

    db_path1 = tmp_path / "run1.sqlite"
    sm1 = SqliteStorageManager(db_path=db_path1)
    gen1 = SyntheticDatasetGenerator(storage_manager=sm1)
    manifest1 = gen1.generate_dataset(cfg)

    db_path2 = tmp_path / "run2.sqlite"
    sm2 = SqliteStorageManager(db_path=db_path2)
    gen2 = SyntheticDatasetGenerator(storage_manager=sm2)
    manifest2 = gen2.generate_dataset(cfg)

    # Stable fingerprint and manifest_id must match across runs
    assert manifest1["manifest_id"] == manifest2["manifest_id"]
    assert manifest1["generator_config_hash"] == manifest2["generator_config_hash"]
    assert manifest1["accel_sample_count"] == manifest2["accel_sample_count"]
    assert manifest1["hr_sample_count"] == manifest2["hr_sample_count"]
    assert manifest1["window_count"] == manifest2["window_count"]
    assert manifest1["training_eligible_count"] == manifest2["training_eligible_count"]

    # Changing seed must produce a different configuration hash and manifest ID
    cfg_diff = GeneratorConfig(
        profile_name="TEST_MINI",
        subject_count=2,
        sessions_per_subject=1,
        session_duration_min=0.5,
        master_seed=999,
    )
    db_path3 = tmp_path / "run3.sqlite"
    sm3 = SqliteStorageManager(db_path=db_path3)
    gen3 = SyntheticDatasetGenerator(storage_manager=sm3)
    manifest3 = gen3.generate_dataset(cfg_diff)

    assert manifest3["manifest_id"] != manifest1["manifest_id"]
    assert manifest3["generator_config_hash"] != manifest1["generator_config_hash"]
