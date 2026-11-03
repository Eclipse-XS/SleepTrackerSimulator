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

from ml.data.label_policy import (  # noqa: F401  (re-exported for backward compatibility)
    CLASSES,
    INT_TO_LABEL,
    LABEL_TO_INT,
    InvalidLabelError,
    encode_label_strict,
    is_supervised_label,
)
from ml.data.window_builder import construct_window_tensor


@dataclass
class WindowDataset:
    """Encapsulates loaded subject data, model-ready tensors, and diagnostic statistics."""

    data_by_subject: dict[str, dict[str, Any]] = field(default_factory=dict)
    total_windows_loaded: int = 0
    training_eligible_count: int = 0
    rejected_count: int = 0
    class_distribution: dict[str, int] = field(default_factory=lambda: {"REST": 0, "RESTLESS": 0, "ACTIVE": 0})
    # Windows dropped because their label is not a supervised class (only with unlabeled_policy="exclude").
    excluded_label_count: int = 0
    excluded_label_audit: dict[str, int] = field(default_factory=dict)

    def get_subject_ids(self) -> list[str]:
        return sorted(self.data_by_subject.keys())

    def keys(self):
        return self.data_by_subject.keys()

    def __getitem__(self, key: str) -> dict[str, Any]:
        return self.data_by_subject[key]

    def __contains__(self, key: str) -> bool:
        return key in self.data_by_subject

    def collate_subjects(
        self,
        subjects: list[str],
        training_only: bool = True,
        policy: str | None = None,
        return_metadata: bool = False,
    ):
        """Collates data strictly from the specified subjects according to eligibility policy.

        Policies:
            - 'TRAINING_STRICT': Filters out training-ineligible windows (only training_eligible == True).
            - 'TEST_ACCEPTED_ONLY': Filters out training-ineligible windows (only training_eligible == True).
            - 'TEST_FULL_POPULATION': Includes all windows (both eligible and ineligible).

        Args:
            subjects: List of subject IDs to include.
            training_only: Legacy boolean flag; if True, equivalent to policy='TRAINING_STRICT'.
            policy: Explicit policy name. Overrides training_only if provided.
            return_metadata: If True, returns (X_accel, X_hr, X_tabular, y, window_ids, session_ids, eligible_mask).
                             If False, returns (X_accel, X_hr, X_tabular, y, window_ids).

        Returns:
            Tuple as specified by return_metadata.
        """
        if policy is not None:
            pol = policy.upper()
            if pol in ("TRAINING_STRICT", "TEST_ACCEPTED_ONLY"):
                filter_ineligible = True
            elif pol == "TEST_FULL_POPULATION":
                filter_ineligible = False
            else:
                raise ValueError(f"Unknown collate policy: '{policy}'. Expected 'TRAINING_STRICT', 'TEST_ACCEPTED_ONLY', or 'TEST_FULL_POPULATION'.")
        else:
            filter_ineligible = training_only

        accel_list, hr_list, tab_list, y_list, wid_list, sess_list, mask_list = [], [], [], [], [], [], []
        for s in subjects:
            if s not in self.data_by_subject:
                continue
            entry = self.data_by_subject[s]
            for i, eligible in enumerate(entry["training_eligible"]):
                if filter_ineligible and not eligible:
                    continue
                accel_list.append(entry["accel_windows"][i])
                hr_list.append(entry["hr_features"][i])
                tab_list.append(entry["tabular_features"][i])
                y_list.append(entry["labels"][i])
                wid_list.append(entry["window_ids"][i])
                sess_list.append(entry["sessions"][i])
                mask_list.append(eligible)

        if not accel_list:
            empty_tup = (
                np.empty((0, 20, 3), dtype=np.float32),
                np.empty((0, 2), dtype=np.float32),
                np.empty((0, 6), dtype=np.float32),
                np.empty((0,), dtype=np.int32),
                [],
            )
            if return_metadata:
                return (*empty_tup, [], np.empty((0,), dtype=bool))
            return empty_tup

        base_tup = (
            np.array(accel_list, dtype=np.float32),
            np.array(hr_list, dtype=np.float32),
            np.array(tab_list, dtype=np.float32),
            np.array(y_list, dtype=np.int32),
            wid_list,
        )
        if return_metadata:
            return (*base_tup, sess_list, np.array(mask_list, dtype=bool))
        return base_tup

    def get_exclusion_audit(self) -> dict[str, Any]:
        """Provides an authoritative breakdown of excluded/ineligible windows by class, subject, and reason."""
        by_subject: dict[str, int] = {}
        by_class: dict[str, int] = {c: 0 for c in CLASSES}
        by_reason: dict[str, int] = {}
        total_ineligible = 0
        total_eligible = 0

        for subj_id, entry in self.data_by_subject.items():
            s_ineligible = 0
            for i, eligible in enumerate(entry["training_eligible"]):
                if not eligible:
                    total_ineligible += 1
                    s_ineligible += 1
                    lbl = INT_TO_LABEL.get(entry["labels"][i], "UNKNOWN")
                    by_class[lbl] = by_class.get(lbl, 0) + 1
                    flags_str = entry["quality_flags"][i]
                    if flags_str:
                        for flag in flags_str.split(","):
                            flag = flag.strip()
                            if flag:
                                by_reason[flag] = by_reason.get(flag, 0) + 1
                    else:
                        by_reason["UNSPECIFIED_INELIGIBLE"] = by_reason.get("UNSPECIFIED_INELIGIBLE", 0) + 1
                else:
                    total_eligible += 1
            if s_ineligible > 0:
                by_subject[subj_id] = s_ineligible

        return {
            "total_windows": total_eligible + total_ineligible,
            "training_eligible_count": total_eligible,
            "ineligible_count": total_ineligible,
            "by_subject": dict(sorted(by_subject.items())),
            "by_class": by_class,
            "by_reason": dict(sorted(by_reason.items(), key=lambda kv: kv[1], reverse=True)),
        }


def load_dataset_from_sqlite(
    db_path: Path | str,
    target_sampling_rate_hz: int = 20,
    enforce_training_eligible: bool = False,
    unlabeled_policy: str = "raise",
) -> WindowDataset:
    """Loads feature windows and maps raw accelerometer records strictly via event-time bounds.

    Rules:
    1. Windows are matched with raw samples by session_id and [w_start_utc, w_end_utc).
    2. Missing samples in earlier windows NEVER shift raw sample indices for later windows.
    3. Missing / sparse windows are safely resampled or marked ineligible.
    4. Causal HR is normalized according to the production contract: (mean_hr - 60) / 40.
    5. Labels must be REST/RESTLESS/ACTIVE. Anything else raises InvalidLabelError (session, window and label
       in the message) unless ``unlabeled_policy="exclude"``, which drops and counts such windows.
    """
    if unlabeled_policy not in ("raise", "exclude"):
        raise ValueError("unlabeled_policy must be 'raise' or 'exclude'")
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

            # Produce [20, 3] model-ready tensor using authoritative unified builder
            accel_tensor, _ = construct_window_tensor(
                w_start_utc=w_start,
                w_end_utc=w_end,
                sample_ts=w_times,
                sample_xyz=w_samples,
                target_sampling_rate_hz=target_sampling_rate_hz,
                max_gap_tolerance_ms=80,
                training_eligible=eligible,
            )

            # HR normalization: causal contract
            norm_bpm = (float(mean_hr) - 60.0) / 40.0 if (hr_avail and mean_hr is not None) else 0.0
            hr_mask = 1.0 if hr_avail else 0.0

            tab_feat = [rms, var, jerk, p2p, norm_bpm, hr_mask]
            if not is_supervised_label(label):
                if unlabeled_policy == "raise":
                    # Raises InvalidLabelError naming session, window and label.
                    encode_label_strict(label, session_id=session_id, window_id=w_id)
                key = repr(label)
                dataset.excluded_label_count += 1
                dataset.excluded_label_audit[key] = dataset.excluded_label_audit.get(key, 0) + 1
                continue
            label_int = LABEL_TO_INT[label]

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
