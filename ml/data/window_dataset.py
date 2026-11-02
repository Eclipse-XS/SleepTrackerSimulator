"""Authoritative Dataset Loader and Feature-to-Tensor Mapper for SleepSense 2.0.

Solves ISSUE ML-002: Reconstructed raw tensors must match feature-window timestamps.
Replaces brittle positional index slicing with exact event-time boundary joins:
    accel_samples.utc_millis in [w_start_utc, w_end_utc)
Ensures later windows are never shifted when earlier samples are dropped.
Provides a unified, shared loader for both runner.py and scientific_audit.py.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

CLASSES = ["REST", "RESTLESS", "ACTIVE"]
LABEL_TO_INT = {c: i for i, c in enumerate(CLASSES)}
INT_TO_LABEL = {i: c for i, c in enumerate(CLASSES)}


@dataclass
class WindowDataset:
    """Encapsulates loaded subject data, model-ready tensors, and diagnostic statistics."""

    data_by_subject: dict[str, dict[str, Any]] = field(default_factory=dict)
    total_windows_loaded: int = 0
    training_eligible_count: int = 0
    rejected_count: int = 0
    class_distribution: dict[str, int] = field(default_factory=lambda: {"REST": 0, "RESTLESS": 0, "ACTIVE": 0})

    def get_subject_ids(self) -> list[str]:
        return sorted(self.data_by_subject.keys())

    def collate_subjects(
        self,
        subjects: list[str],
        training_only: bool = True,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, list[str]]:
        """Collates data strictly from the specified subjects.

        Args:
            subjects: List of subject IDs to include.
            training_only: If True, filters out training-ineligible windows (e.g. dropouts/large gaps).

        Returns:
            (X_accel, X_hr, X_tabular, y, window_ids)
        """
        accel_list, hr_list, tab_list, y_list, wid_list = [], [], [], [], []
        for s in subjects:
            if s not in self.data_by_subject:
                continue
            entry = self.data_by_subject[s]
            for i, eligible in enumerate(entry["training_eligible"]):
                if training_only and not eligible:
                    continue
                accel_list.append(entry["accel_windows"][i])
                hr_list.append(entry["hr_features"][i])
                tab_list.append(entry["tabular_features"][i])
                y_list.append(entry["labels"][i])
                wid_list.append(entry["window_ids"][i])

        if not accel_list:
            return (
                np.empty((0, 20, 3), dtype=np.float32),
                np.empty((0, 2), dtype=np.float32),
                np.empty((0, 6), dtype=np.float32),
                np.empty((0,), dtype=np.int32),
                [],
            )

        return (
            np.array(accel_list, dtype=np.float32),
            np.array(hr_list, dtype=np.float32),
            np.array(tab_list, dtype=np.float32),
            np.array(y_list, dtype=np.int32),
            wid_list,
        )


def load_dataset_from_sqlite(
    db_path: Path | str,
    target_sampling_rate_hz: int = 20,
    enforce_training_eligible: bool = False,
) -> WindowDataset:
    """Loads feature windows and maps raw accelerometer records strictly via event-time bounds.

    Rules:
    1. Windows are matched with raw samples by session_id and [w_start_utc, w_end_utc).
    2. Missing samples in earlier windows NEVER shift raw sample indices for later windows.
    3. Missing / sparse windows are safely resampled or marked ineligible.
    4. Causal HR is normalized according to the production contract: (mean_hr - 60) / 40.
    """
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row

    # Check available columns in feature_windows (support both v2.0.0 and v2.1.0 schemas)
    cursor = conn.execute("PRAGMA table_info(feature_windows);")
    columns = {row["name"] for row in cursor.fetchall()}
    has_training_eligible = "training_eligible" in columns
    has_quality_flags = "quality_flags" in columns
    has_max_gap = "max_gap_ms" in columns

    # 1. Fetch all subjects and sessions
    sessions_query = """
        SELECT s.session_id, s.subject_id, s.device_placement
        FROM sessions s
        ORDER BY s.subject_id, s.session_id;
    """
    sessions_df = conn.execute(sessions_query).fetchall()

    dataset = WindowDataset()

    for sess_row in sessions_df:
        session_id = sess_row["session_id"]
        subject_id = sess_row["subject_id"]
        placement = sess_row["device_placement"]

        if subject_id not in dataset.data_by_subject:
            dataset.data_by_subject[subject_id] = {
                "subject_id": subject_id,
                "placement": placement,
                "sessions": [],
                "accel_windows": [],
                "hr_features": [],
                "tabular_features": [],
                "labels": [],
                "window_ids": [],
                "training_eligible": [],
                "quality_flags": [],
            }

        # 2. Fetch feature windows for session
        fw_query = f"""
            SELECT window_id, start_utc_millis, end_utc_millis, sample_count, coverage_fraction,
                   motion_rms, variance, jerk, peak_to_peak, mean_hr, hr_available, ground_truth_label
                   {', training_eligible' if has_training_eligible else ''}
                   {', quality_flags' if has_quality_flags else ''}
                   {', max_gap_ms' if has_max_gap else ''}
            FROM feature_windows
            WHERE session_id = ?
            ORDER BY start_utc_millis ASC;
        """
        fw_rows = conn.execute(fw_query, (session_id,)).fetchall()
        if not fw_rows:
            continue

        # 3. Fetch all raw accelerometer samples for session ordered by utc_millis
        raw_samples = conn.execute(
            """
            SELECT utc_millis, x, y, z
            FROM accelerometer_samples
            WHERE session_id = ?
            ORDER BY utc_millis ASC;
            """,
            (session_id,),
        ).fetchall()

        if raw_samples:
            raw_ts = np.array([r["utc_millis"] for r in raw_samples], dtype=np.int64)
            raw_xyz = np.array([[r["x"], r["y"], r["z"]] for r in raw_samples], dtype=np.float32)
        else:
            raw_ts = np.array([], dtype=np.int64)
            raw_xyz = np.empty((0, 3), dtype=np.float32)

        # 4. Join each feature window with raw samples using event-time boundaries
        for fw in fw_rows:
            w_id = fw["window_id"]
            w_start = fw["start_utc_millis"]
            w_end = fw["end_utc_millis"]
            rms = float(fw["motion_rms"])
            var = float(fw["variance"])
            jerk = float(fw["jerk"])
            p2p = float(fw["peak_to_peak"])
            mean_hr = fw["mean_hr"]
            hr_avail = int(fw["hr_available"])
            label = fw["ground_truth_label"]

            eligible = bool(fw["training_eligible"]) if has_training_eligible else (fw["sample_count"] == 20)
            q_flags = fw["quality_flags"] if has_quality_flags else ""

            # Slice raw samples in [w_start, w_end)
            if len(raw_ts) > 0:
                l_idx = np.searchsorted(raw_ts, w_start, side="left")
                r_idx = np.searchsorted(raw_ts, w_end, side="left")
                w_samples = raw_xyz[l_idx:r_idx]
                w_times = raw_ts[l_idx:r_idx]
            else:
                w_samples = np.empty((0, 3), dtype=np.float32)
                w_times = np.array([], dtype=np.int64)

            # Produce [20, 3] model-ready tensor
            if len(w_samples) == target_sampling_rate_hz:
                accel_tensor = w_samples
            elif len(w_samples) >= 2:
                # Resample onto uniform 20-point grid
                target_grid = w_start + (np.arange(target_sampling_rate_hz) + 0.5) * (1000.0 / target_sampling_rate_hz)
                interp_x = np.interp(target_grid, w_times, w_samples[:, 0]).astype(np.float32)
                interp_y = np.interp(target_grid, w_times, w_samples[:, 1]).astype(np.float32)
                interp_z = np.interp(target_grid, w_times, w_samples[:, 2]).astype(np.float32)
                accel_tensor = np.column_stack([interp_x, interp_y, interp_z])
            elif len(w_samples) > 0:
                pad_len = target_sampling_rate_hz - len(w_samples)
                accel_tensor = np.pad(w_samples, ((0, pad_len), (0, 0)), mode="edge")
            else:
                accel_tensor = np.zeros((target_sampling_rate_hz, 3), dtype=np.float32)

            # HR normalization: causal contract
            norm_bpm = (float(mean_hr) - 60.0) / 40.0 if (hr_avail and mean_hr is not None) else 0.0
            hr_mask = 1.0 if hr_avail else 0.0

            tab_feat = [rms, var, jerk, p2p, norm_bpm, hr_mask]
            label_int = LABEL_TO_INT.get(label, 0)

            # Record into dataset
            entry = dataset.data_by_subject[subject_id]
            entry["sessions"].append(session_id)
            entry["window_ids"].append(w_id)
            entry["accel_windows"].append(accel_tensor)
            entry["hr_features"].append([norm_bpm, hr_mask])
            entry["tabular_features"].append(tab_feat)
            entry["labels"].append(label_int)
            entry["training_eligible"].append(eligible)
            entry["quality_flags"].append(q_flags)

            dataset.total_windows_loaded += 1
            if eligible:
                dataset.training_eligible_count += 1
            else:
                dataset.rejected_count += 1
            if label in dataset.class_distribution:
                dataset.class_distribution[label] += 1

    conn.close()
    return dataset
