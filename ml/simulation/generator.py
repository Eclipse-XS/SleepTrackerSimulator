"""Master Hierarchical Synthetic Sensor Dataset Generator for SleepSense 2.0.

Orchestrates population generation, semi-Markov state simulation, biomechanical
accelerometer modeling, physiological HR inertia, fault injection, ground-truth
interval alignment, and bounded-memory streaming persistence to SQLite and Parquet.
Utilizes the authoritative Event-Time WindowBuilder (ml.data.window_builder).
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

import polars as pl
from pyarrow import parquet as pq

from ml.data.sqlite_storage import OutputPolicy, SqliteStorageManager
from ml.data.window_builder import WindowQualityPolicy, build_session_windows
from ml.simulation.accelerometer_simulator import AccelerometerSimulator
from ml.simulation.failure_simulator import FailureSimulator
from ml.simulation.heart_rate_simulator import HeartRateSimulator
from ml.simulation.state_machine import SemiMarkovStateSimulator
from ml.simulation.subject_profile import SubjectPopulationGenerator

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
        output_policy: OutputPolicy = OutputPolicy.REPLACE_GENERATED_DATA,
    ) -> dict[str, Any]:
        """Executes full reproducible dataset generation.

        Streams session-by-session into SQLite and Parquet to maintain bounded RAM (<100MB).
        Uses authoritative event-time windowing with causal heart-rate alignment.
        """
        def log(msg: str) -> None:
            if progress_callback:
                progress_callback(msg)
            else:
                print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")

        log(f"Starting synthetic generation for profile '{config.profile_name}' (seed={config.master_seed})...")

        # 1. Initialize SQLite schema safely according to output policy
        self.storage.output_policy = output_policy
        self.storage.init_schema(recreate=True, force_replace=True)

        # 2. Generate Virtual Subjects
        subjects = SubjectPopulationGenerator.generate_population(
            count=config.subject_count,
            seed=config.master_seed,
            domain_shift=config.domain_shift,
        )
        self.storage.insert_subjects(subjects)
        log(f"Generated and persisted {len(subjects)} virtual subjects.")

        # Configuration fingerprint (deterministic identity)
        config_dict = {
            "profile": config.profile_name,
            "subjects": config.subject_count,
            "sessions_per_subject": config.sessions_per_subject,
            "duration_min": config.session_duration_min,
            "seed": config.master_seed,
            "domain_shift": config.domain_shift,
            "fault_scenario": config.fault_scenario,
            "sampling_rate_hz": config.sampling_rate_hz,
            "schema_version": "2.1.0",
        }
        config_hash = hashlib.sha256(json.dumps(config_dict, sort_keys=True).encode("utf-8")).hexdigest()[:16]

        total_sessions = 0
        total_accel_samples = 0
        total_hr_samples = 0
        total_gt_intervals = 0
        total_windows = 0
        total_quality_events = 0
        total_training_eligible = 0

        parquet_path = Path(parquet_output_dir) if parquet_output_dir is not None else None
        if parquet_path is not None:
            parquet_path.mkdir(parents=True, exist_ok=True)

        base_start_utc = 1730000000000  # Epoch millis reference
        failure_sim = FailureSimulator(seed=config.master_seed + 77)
        quality_policy = WindowQualityPolicy(target_sampling_rate_hz=config.sampling_rate_hz)

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

                # F. Authoritative Event-Time Feature Window Building
                build_res = build_session_windows(
                    session_id=session_id,
                    session_start_ms=start_utc_ms,
                    session_end_ms=end_utc_ms,
                    raw_accelerometer_samples=mod_accel,
                    raw_heart_rate_samples=mod_hr,
                    ground_truth_intervals=intervals,
                    policy=quality_policy,
                )
                windows = build_res.windows
                self.storage.insert_feature_windows_batch(windows)
                total_windows += len(windows)
                total_training_eligible += build_res.accepted_count

                # Bounded-memory streaming Parquet persistence (session-by-session)
                if parquet_path is not None and windows:
                    df_sess = pl.DataFrame(windows)
                    pq.write_to_dataset(
                        df_sess.to_arrow(),
                        root_path=str(parquet_path),
                        partition_cols=["ground_truth_label"],
                        existing_data_behavior="overwrite_or_ignore",
                        compression="SNAPPY",
                    )

                total_sessions += 1

            log(f"Processed subject {sub_idx + 1}/{len(subjects)} ({subject.subject_id}): {config.sessions_per_subject} sessions.")

        if parquet_path is not None:
            log(f"Streamed feature windows to partitioned Parquet at: {parquet_path}")

        # 4. Record Deterministic Dataset Manifest
        total_duration_hours = (total_sessions * config.session_duration_min) / 60.0
        manifest_id = f"MANIFEST-{config.profile_name}-{config_hash}"
        manifest = {
            "manifest_id": manifest_id,
            "dataset_version": f"2.1.0-{config.profile_name.lower()}",
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
            "training_eligible_count": total_training_eligible,
            "schema_version": "2.1.0",
        }
        self.storage.record_manifest(manifest)

        # 5. Verify Referential Integrity
        fk_violations = self.storage.verify_referential_integrity()
        if fk_violations:
            raise RuntimeError(f"Database referential integrity violations: {fk_violations}")

        log(f"Referential integrity verified: 0 foreign key violations.")
        log(
            f"Generation complete! Total sessions={total_sessions}, "
            f"Accel samples={total_accel_samples:,}, HR samples={total_hr_samples:,}, "
            f"Windows={total_windows:,} (eligible={total_training_eligible:,})."
        )

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
        """Backward-compatible proxy forwarding to authoritative build_session_windows."""
        end_utc_ms = start_utc_ms + (len(accel_samples) // window_size) * 1000
        res = build_session_windows(
            session_id=session_id,
            session_start_ms=start_utc_ms,
            session_end_ms=end_utc_ms,
            raw_accelerometer_samples=accel_samples,
            raw_heart_rate_samples=hr_samples,
            ground_truth_intervals=intervals,
        )
        return res.windows
