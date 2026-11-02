"""Master Hierarchical Synthetic Sensor Dataset Generator.

Orchestrates population generation, semi-Markov state simulation, biomechanical
accelerometer modeling, physiological HR inertia, fault injection, ground-truth
interval alignment, and bounded-memory streaming persistence to SQLite and Parquet.
"""

from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import numpy as np
import polars as pl
from pyarrow import parquet as pq

from ml.data.sqlite_storage import SqliteStorageManager
from ml.simulation.accelerometer_simulator import AccelerometerSimulator
from ml.simulation.failure_simulator import FailureSimulator
from ml.simulation.heart_rate_simulator import HeartRateSimulator
from ml.simulation.state_machine import STATE_NAMES, SemiMarkovStateSimulator
from ml.simulation.subject_profile import SubjectPopulationGenerator, VirtualSubject

ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class GeneratorConfig:
    profile_name: str
    subject_count: int
    sessions_per_subject: int
    session_duration_min: float
    sampling_rate_hz: int = 20
    master_seed: int = 42
    domain_shift: bool = False
    fault_scenario: str = "standard"


PROFILES = {
    "SMALL": GeneratorConfig(
        profile_name="SMALL",
        subject_count=12,
        sessions_per_subject=3,
        session_duration_min=30.0,
        master_seed=42,
    ),
    "MEDIUM": GeneratorConfig(
        profile_name="MEDIUM",
        subject_count=24,
        sessions_per_subject=4,
        session_duration_min=45.0,
        master_seed=101,
    ),
    "LONG_SESSION": GeneratorConfig(
        profile_name="LONG_SESSION",
        subject_count=4,
        sessions_per_subject=1,
        session_duration_min=360.0,  # 6 hours
        master_seed=2024,
    ),
    "SHIFTED": GeneratorConfig(
        profile_name="SHIFTED",
        subject_count=10,
        sessions_per_subject=3,
        session_duration_min=30.0,
        master_seed=999,
        domain_shift=True,
        fault_scenario="heavy_dropout",
    ),
}


class SyntheticDatasetGenerator:
    """End-to-end dataset generation pipeline with bounded memory."""

    def __init__(self, storage_manager: SqliteStorageManager | None = None):
        self.storage = storage_manager or SqliteStorageManager()

    def generate_dataset(
        self,
        config: GeneratorConfig,
        parquet_output_dir: Path | str | None = None,
        progress_callback: Callable[[str], None] | None = None,
    ) -> dict[str, Any]:
        """Executes full reproducible dataset generation.

        Streams session-by-session into SQLite to maintain bounded RAM (<500MB).
        Optionally exports feature windows to Parquet.
        """
        def log(msg: str) -> None:
            if progress_callback:
                progress_callback(msg)
            else:
                print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")

        log(f"Starting synthetic generation for profile '{config.profile_name}' (seed={config.master_seed})...")

        # 1. Initialize SQLite schema
        self.storage.init_schema(recreate=True)

        # 2. Generate Virtual Subjects
        subjects = SubjectPopulationGenerator.generate_population(
            count=config.subject_count,
            seed=config.master_seed,
            domain_shift=config.domain_shift,
        )
        self.storage.insert_subjects(subjects)
        log(f"Generated and persisted {len(subjects)} virtual subjects.")

        # Configuration hash
        config_dict = {
            "profile": config.profile_name,
            "subjects": config.subject_count,
            "sessions_per_subject": config.sessions_per_subject,
            "duration_min": config.session_duration_min,
            "seed": config.master_seed,
            "domain_shift": config.domain_shift,
        }
        config_hash = hashlib.sha256(json.dumps(config_dict, sort_keys=True).encode("utf-8")).hexdigest()[:16]

        total_sessions = 0
        total_accel_samples = 0
        total_hr_samples = 0
        total_gt_intervals = 0
        total_windows = 0
        total_quality_events = 0

        # Memory buffer for Parquet export
        parquet_feature_rows: list[dict[str, Any]] = []

        base_start_utc = 1730000000000  # Epoch millis reference

        failure_sim = FailureSimulator(seed=config.master_seed + 77)

        # 3. Generate Sessions per Subject in Bounded Chunks
        for sub_idx, subject in enumerate(subjects):
            for sess_idx in range(config.sessions_per_subject):
                session_num = total_sessions + 1
                session_id = f"SESS-{config.profile_name[:3]}-{session_num:04d}"
                session_seed = config.master_seed + session_num * 100 + sub_idx * 10 + sess_idx

                # Session start time spaced across successive nights
                start_utc_ms = base_start_utc + (sub_idx * 7 + sess_idx) * 86_400_000 + sess_idx * 3_600_000
                duration_sec = config.session_duration_min * 60.0
                end_utc_ms = start_utc_ms + int(duration_sec * 1000.0)

                # A. Simulate Latent States
                state_sim = SemiMarkovStateSimulator(subject, seed=session_seed)
                intervals = state_sim.simulate_timeline(session_id, total_duration_sec=duration_sec)
                total_gt_intervals += len(intervals)

                # B. Simulate Accelerometer
                accel_sim = AccelerometerSimulator(subject, seed=session_seed + 1)
                raw_accel_samples: list[dict[str, Any]] = []
                for interval in intervals:
                    int_start_utc = start_utc_ms + int(round(interval.start_sec * 1000))
                    int_start_elapsed = int(round(interval.start_sec * 1e9))
                    int_samples = accel_sim.generate_interval_signal(interval, int_start_utc, int_start_elapsed)
                    raw_accel_samples.extend(int_samples)

                # C. Simulate BLE Heart Rate
                hr_sim = HeartRateSimulator(subject, seed=session_seed + 2)
                raw_hr_samples = hr_sim.simulate_session_hr(intervals, start_utc_ms)

                # D. Inject Faults and Log Quality Events
                mod_accel, mod_hr, q_events = failure_sim.inject_faults(
                    session_id=session_id,
                    accel_samples=raw_accel_samples,
                    hr_samples=raw_hr_samples,
                    scenario=config.fault_scenario,
                )
                total_quality_events += len(q_events)

                # E. Persist Session Metadata FIRST to satisfy Foreign Key constraints
                quality_status = "DEGRADED" if q_events else "EXCELLENT"
                self.storage.insert_session({
                    "session_id": session_id,
                    "subject_id": subject.subject_id,
                    "start_utc_millis": start_utc_ms,
                    "end_utc_millis": end_utc_ms,
                    "duration_sec": duration_sec,
                    "device_placement": subject.preferred_placement,
                    "sampling_rate_hz": config.sampling_rate_hz,
                    "simulation_seed": session_seed,
                    "source_kind": "SIMULATED",
                    "generator_version": subject.generator_version,
                    "quality_status": quality_status,
                })

                # Persist ground truth intervals and quality events
                self.storage.insert_ground_truth_batch(intervals, start_utc_ms)
                self.storage.insert_quality_events_batch(q_events)

                inserted_accel = self.storage.insert_accelerometer_samples_batch(mod_accel)
                inserted_hr = self.storage.insert_heart_rate_samples_batch(mod_hr)

                total_accel_samples += inserted_accel
                total_hr_samples += inserted_hr

                # F. Segment Synchronized Feature Windows with Leakage-Free Ground Truth
                windows = self._extract_session_windows(
                    session_id=session_id,
                    accel_samples=mod_accel,
                    hr_samples=mod_hr,
                    intervals=intervals,
                    start_utc_ms=start_utc_ms,
                    window_size=20,
                )
                self.storage.insert_feature_windows_batch(windows)
                total_windows += len(windows)
                parquet_feature_rows.extend(windows)

                total_sessions += 1

            log(f"Processed subject {sub_idx + 1}/{len(subjects)} ({subject.subject_id}): {config.sessions_per_subject} sessions.")

        # 4. Optional Parquet Export
        if parquet_output_dir is not None:
            parquet_path = Path(parquet_output_dir)
            parquet_path.mkdir(parents=True, exist_ok=True)
            df_feat = pl.DataFrame(parquet_feature_rows)

            # Write partitioned dataset
            arrow_table = df_feat.to_arrow()
            pq.write_to_dataset(
                arrow_table,
                root_path=str(parquet_path),
                partition_cols=["ground_truth_label"],
                existing_data_behavior="overwrite_or_ignore",
                compression="SNAPPY",
            )
            log(f"Exported {len(df_feat)} feature windows to Parquet at: {parquet_path}")

        # 5. Record Dataset Manifest
        total_duration_hours = (total_sessions * config.session_duration_min) / 60.0
        manifest_id = f"MANIFEST-{config.profile_name}-{int(time.time())}"
        manifest = {
            "manifest_id": manifest_id,
            "dataset_version": f"2.0.0-{config.profile_name.lower()}",
            "dataset_profile": config.profile_name,
            "generator_config_hash": config_hash,
            "master_seed": config.master_seed,
            "created_at_utc": datetime.now(timezone.utc).isoformat(),
            "subject_count": len(subjects),
            "session_count": total_sessions,
            "total_duration_hours": round(total_duration_hours, 2),
            "accel_sample_count": total_accel_samples,
            "hr_sample_count": total_hr_samples,
            "window_count": total_windows,
            "ground_truth_count": total_gt_intervals,
            "quality_event_count": total_quality_events,
            "schema_version": "2.0.0",
        }
        self.storage.record_manifest(manifest)

        # 6. Verify Referential Integrity
        fk_violations = self.storage.verify_referential_integrity()
        if fk_violations:
            raise RuntimeError(f"Database referential integrity violations: {fk_violations}")

        log(f"Referential integrity verified: 0 foreign key violations.")
        log(f"Generation complete! Total sessions={total_sessions}, Accel samples={total_accel_samples:,}, HR samples={total_hr_samples:,}, Windows={total_windows:,}.")

        return manifest

    def _extract_session_windows(
        self,
        session_id: str,
        accel_samples: list[dict[str, Any]],
        hr_samples: list[dict[str, Any]],
        intervals: Sequence[Any],
        start_utc_ms: int,
        window_size: int = 20,
    ) -> list[dict[str, Any]]:
        """Segments raw samples into 20-sample windows and extracts features with ground truth."""
        if len(accel_samples) < window_size:
            return []

        windows: list[dict[str, Any]] = []
        n_windows = len(accel_samples) // window_size
        dt_sec = 0.05

        # Create quick lookup for HR samples by utc_millis
        hr_times = np.array([h["utc_millis"] for h in hr_samples], dtype=np.int64) if hr_samples else np.array([], dtype=np.int64)
        hr_bpms = np.array([h["bpm"] for h in hr_samples], dtype=np.float32) if hr_samples else np.array([], dtype=np.float32)

        # Create interval boundaries in ms
        int_starts_ms = np.array([start_utc_ms + int(round(it.start_sec * 1000)) for it in intervals], dtype=np.int64)
        int_ends_ms = np.array([start_utc_ms + int(round(it.end_sec * 1000)) for it in intervals], dtype=np.int64)
        int_labels = [it.state_name for it in intervals]

        for w_idx in range(n_windows):
            start_i = w_idx * window_size
            end_i = start_i + window_size
            w_samples = accel_samples[start_i:end_i]

            w_start_utc = w_samples[0]["utc_millis"]
            w_end_utc = w_samples[-1]["utc_millis"]

            # Triaxial arrays
            xs = np.array([s["x"] for s in w_samples], dtype=np.float32)
            ys = np.array([s["y"] for s in w_samples], dtype=np.float32)
            zs = np.array([s["z"] for s in w_samples], dtype=np.float32)

            # Center dynamic acceleration (gravity removal)
            dyn_x = xs - np.mean(xs)
            dyn_y = ys - np.mean(ys)
            dyn_z = zs - np.mean(zs)

            mags = np.sqrt(dyn_x**2 + dyn_y**2 + dyn_z**2)
            rms = float(np.sqrt(np.mean(mags**2)))
            variance = float(np.var(mags))
            peak_to_peak = float(np.max(mags) - np.min(mags))

            # Jerk
            jerk = np.diff(mags) / dt_sec
            jerk_rms = float(np.sqrt(np.mean(jerk**2))) if len(jerk) > 0 else 0.0

            # Heart rate within [w_start_utc - 10_000, w_end_utc + 1_000]
            if len(hr_times) > 0:
                mask = (hr_times >= (w_start_utc - 10_000)) & (hr_times <= (w_end_utc + 1_000))
                matching_bpms = hr_bpms[mask]
                if len(matching_bpms) > 0:
                    mean_hr = float(np.mean(matching_bpms))
                    hr_available = 1
                else:
                    mean_hr = None
                    hr_available = 0
            else:
                mean_hr = None
                hr_available = 0

            # Ground truth assignment from latent state interval overlap (center of window)
            w_center_utc = (w_start_utc + w_end_utc) // 2
            gt_label = "REST"
            for k in range(len(int_starts_ms)):
                if int_starts_ms[k] <= w_center_utc <= int_ends_ms[k]:
                    gt_label = int_labels[k]
                    break

            window_id = f"{session_id}-w{w_idx:05d}"
            windows.append({
                "window_id": window_id,
                "session_id": session_id,
                "start_utc_millis": w_start_utc,
                "end_utc_millis": w_end_utc,
                "sample_count": window_size,
                "coverage_fraction": 1.0,
                "motion_rms": round(rms, 6),
                "variance": round(variance, 6),
                "jerk": round(jerk_rms, 6),
                "peak_to_peak": round(peak_to_peak, 6),
                "mean_hr": round(mean_hr, 1) if mean_hr is not None else None,
                "hr_available": hr_available,
                "ground_truth_label": gt_label,
                "provenance": "SIMULATED",
            })

        return windows
