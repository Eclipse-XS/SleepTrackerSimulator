"""Tests for Bitwise and Numerical Parity in Unified Tensor Construction.

Verifies that WindowBuilder and WindowDataset produce identical float32 tensors
down to numerical tolerance (1e-6) and byte-identical SHA-256 hashes,
specifically resolving divergence when 20 samples have irregular timestamps.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
import sqlite3

import numpy as np
import pytest

from ml.data.sqlite_storage import OutputPolicy, SqliteStorageManager
from ml.data.window_builder import (
    WindowQualityPolicy,
    build_session_windows,
    construct_window_tensor,
)
from ml.data.window_dataset import load_dataset_from_sqlite


def compute_tensor_hash(tensor: np.ndarray) -> str:
    """Computes SHA-256 hash of tensor bytes."""
    return hashlib.sha256(tensor.astype(np.float32).tobytes()).hexdigest()


def test_irregular_20_samples_parity(tmp_path: Path):
    """Fixture with exactly 20 samples but irregular spacing (max gap > 80ms).

    Ensures that both WindowBuilder and WindowDataset detect the irregular spacing
    and interpolate onto the same uniform time grid, yielding matching tensors and hashes.
    """
    w_start = 10000
    w_end = 11000  # 1000ms duration

    # Create 20 timestamps with an irregular spacing:
    # 10 samples clustered early, one gap of 150ms (> 80ms tolerance), 10 samples later
    ts_part1 = np.linspace(w_start + 10, w_start + 300, 10, dtype=np.int64)
    ts_part2 = np.linspace(w_start + 450, w_end - 10, 10, dtype=np.int64)  # gap between 300 and 450 is 150ms
    sample_ts = np.concatenate([ts_part1, ts_part2])
    assert len(sample_ts) == 20

    # 3D simulated acceleration
    xyz = np.zeros((20, 3), dtype=np.float32)
    for i in range(20):
        xyz[i, 0] = float(np.sin(i * 0.3))
        xyz[i, 1] = float(np.cos(i * 0.3))
        xyz[i, 2] = float(9.81 + 0.1 * i)

    # 1. Direct call to construct_window_tensor
    tensor_direct, was_resampled_direct = construct_window_tensor(
        w_start_utc=w_start,
        w_end_utc=w_end,
        sample_ts=sample_ts,
        sample_xyz=xyz,
        target_sampling_rate_hz=20,
        max_gap_tolerance_ms=80,
        training_eligible=True,
    )
    assert was_resampled_direct == 1, "Expected resampling due to 150ms gap > 80ms tolerance"
    assert tensor_direct.shape == (20, 3)

    # 2. WindowBuilder execution
    raw_accel = [
        {
            "session_id": "SESS-PARITY-001",
            "elapsed_nanos": int(sample_ts[i] * 1_000_000),
            "utc_millis": int(sample_ts[i]),
            "x": float(xyz[i, 0]),
            "y": float(xyz[i, 1]),
            "z": float(xyz[i, 2]),
        }
        for i in range(20)
    ]
    raw_hr = [{
        "sample_id": "HR-001",
        "session_id": "SESS-PARITY-001",
        "device_id": "BLE-PARITY",
        "utc_millis": w_start + 500,
        "bpm": 65,
    }]
    intervals = [{"start_utc_millis": w_start, "end_utc_millis": w_end, "state_label": "REST"}]

    build_res = build_session_windows(
        session_id="SESS-PARITY-001",
        session_start_ms=w_start,
        session_end_ms=w_end,
        raw_accelerometer_samples=raw_accel,
        raw_heart_rate_samples=raw_hr,
        ground_truth_intervals=intervals,
        policy=WindowQualityPolicy(target_sampling_rate_hz=20),
    )
    assert len(build_res.windows) == 1
    tensor_builder = build_res.model_tensors[0]
    assert build_res.windows[0]["was_resampled"] == 1

    # 3. Store in SQLite and reload via load_dataset_from_sqlite
    db_path = tmp_path / "parity_test.sqlite"
    sm = SqliteStorageManager(db_path=db_path, output_policy=OutputPolicy.REPLACE_GENERATED_DATA)
    sm.init_schema(recreate=True, force_replace=True)

    with sm.session_scope() as conn:
        conn.execute("""
            INSERT INTO subjects (subject_id, baseline_hr, hr_response_amplitude, hr_tau_rise_sec,
                hr_tau_recovery_sec, hr_variability_std, movement_scale, restlessness_tendency,
                preferred_placement, device_orientation_euler, sensor_noise_std, ble_packet_loss_rate,
                typical_duration_min, generator_version, created_at_utc_ms)
            VALUES ('SUBJ-PARITY', 60.0, 15.0, 10.0, 30.0, 2.0, 1.0, 0.2, 'CHEST', '[]', 0.05, 0.01, 30.0, '2.1.0', 1000);
        """)
        conn.execute("""
            INSERT INTO sessions (session_id, subject_id, start_utc_millis, end_utc_millis,
                duration_sec, device_placement, sampling_rate_hz, simulation_seed, source_kind, generator_version, quality_status)
            VALUES ('SESS-PARITY-001', 'SUBJ-PARITY', 10000, 11000, 1.0, 'CHEST', 20, 42, 'SIMULATED', '2.1.0', 'EXCELLENT');
        """)

    sm.insert_accelerometer_samples_batch(raw_accel)
    sm.insert_heart_rate_samples_batch(raw_hr)
    sm.insert_feature_windows_batch(build_res.windows)

    dataset = load_dataset_from_sqlite(db_path)
    entry = dataset.data_by_subject["SUBJ-PARITY"]
    tensor_reloaded = entry["accel_windows"][0]

    # Parity assertions:
    # A. All tensors must match within floating-point tolerance (1e-6)
    assert np.allclose(tensor_direct, tensor_builder, atol=1e-6), "Direct and Builder tensors diverged"
    assert np.allclose(tensor_builder, tensor_reloaded, atol=1e-6), "Builder and Reloaded SQL tensors diverged"

    # B. Exact bitwise content hash match
    hash_direct = compute_tensor_hash(tensor_direct)
    hash_builder = compute_tensor_hash(tensor_builder)
    hash_reloaded = compute_tensor_hash(tensor_reloaded)

    assert hash_direct == hash_builder, f"Hash mismatch: direct={hash_direct}, builder={hash_builder}"
    assert hash_builder == hash_reloaded, f"Hash mismatch: builder={hash_builder}, reloaded={hash_reloaded}"


def test_uniform_20_samples_direct_placement():
    """Fixture with exactly 20 uniformly spaced samples (gaps ~50ms <= 80ms).

    Ensures that direct placement is used (was_resampled=0) and the raw values are preserved.
    """
    w_start = 0
    w_end = 1000
    sample_ts = np.array([25 + i * 50 for i in range(20)], dtype=np.int64)
    xyz = np.random.RandomState(42).randn(20, 3).astype(np.float32)

    tensor, was_resampled = construct_window_tensor(
        w_start_utc=w_start,
        w_end_utc=w_end,
        sample_ts=sample_ts,
        sample_xyz=xyz,
        target_sampling_rate_hz=20,
        max_gap_tolerance_ms=80,
        training_eligible=True,
    )
    assert was_resampled == 0
    assert np.allclose(tensor, xyz)
    assert compute_tensor_hash(tensor) == compute_tensor_hash(xyz)


def test_ineligible_sparse_window_fallback():
    """Ineligible window with only 3 samples -> ensures consistent edge padding fallback."""
    w_start = 0
    w_end = 1000
    sample_ts = np.array([100, 200, 300], dtype=np.int64)
    xyz = np.array([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0], [7.0, 8.0, 9.0]], dtype=np.float32)

    tensor, was_resampled = construct_window_tensor(
        w_start_utc=w_start,
        w_end_utc=w_end,
        sample_ts=sample_ts,
        sample_xyz=xyz,
        target_sampling_rate_hz=20,
        max_gap_tolerance_ms=80,
        training_eligible=False,
    )
    assert was_resampled == 0
    assert tensor.shape == (20, 3)
    assert np.allclose(tensor[0], [1.0, 2.0, 3.0])
    assert np.allclose(tensor[1], [4.0, 5.0, 6.0])
    assert np.allclose(tensor[2], [7.0, 8.0, 9.0])
    # Padded samples match edge (last sample)
    for k in range(3, 20):
        assert np.allclose(tensor[k], [7.0, 8.0, 9.0])
