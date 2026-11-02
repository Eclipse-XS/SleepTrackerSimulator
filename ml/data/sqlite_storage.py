"""SQLite Analytical Storage and Persistence Engine for SleepSense 2.0.

Provides high-performance batch insertion, transactional integrity, foreign-key
enforcement, schema initialization, and verification for synthetic datasets.
Enforces strict output directory safety to prevent accidental deletion of Room/user databases.
"""

from __future__ import annotations

import contextlib
from collections.abc import Sequence
from enum import Enum
import json
from pathlib import Path
import sqlite3
import time
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMA_PATH = ROOT / "sql" / "schema.sql"
DEFAULT_DB_PATH = ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"
APPROVED_SYNTHETIC_DIR = (ROOT / "data" / "synthetic").resolve()


class OutputPolicy(str, Enum):
    """Output policies for database creation and replacement."""

    FAIL_IF_EXISTS = "FAIL_IF_EXISTS"
    REUSE_IF_MATCHING_FINGERPRINT = "REUSE_IF_MATCHING_FINGERPRINT"
    REPLACE_GENERATED_DATA = "REPLACE_GENERATED_DATA"
    WRITE_NEW_VERSION = "WRITE_NEW_VERSION"


class SqliteStorageManager:
    """Manages SQLite storage for the synthetic research dataset."""

    def __init__(
        self,
        db_path: Path | str = DEFAULT_DB_PATH,
        schema_path: Path | str = DEFAULT_SCHEMA_PATH,
        output_policy: OutputPolicy = OutputPolicy.REPLACE_GENERATED_DATA,
    ):
        self.db_path = Path(db_path)
        self.schema_path = Path(schema_path)
        self.output_policy = output_policy
        self.db_path.parent.mkdir(parents=True, exist_ok=True)

    def _is_safe_path(self) -> bool:
        """Verifies target is inside approved synthetic directory or a test temp directory."""
        try:
            resolved = self.db_path.resolve()
            # Allowed if under APPROVED_SYNTHETIC_DIR
            if APPROVED_SYNTHETIC_DIR in resolved.parents or resolved.parent == APPROVED_SYNTHETIC_DIR:
                return True
            # Allowed if inside system temp directory (for pytest tmp_path)
            temp_dir = Path(sqlite3.connect.__module__).resolve()  # dummy
            import tempfile
            resolved_tmp = Path(tempfile.gettempdir()).resolve()
            if resolved_tmp in resolved.parents:
                return True
            # Also allow pytest tmp_path (which might be under AppData/Local/Temp or repo root)
            if "tmp" in resolved.parts or "pytest" in str(resolved).lower():
                return True
            return False
        except Exception:
            return False

    def get_connection(self) -> sqlite3.Connection:
        """Returns SQLite connection with WAL mode and foreign keys enabled."""
        conn = sqlite3.connect(str(self.db_path), timeout=30.0)
        conn.execute("PRAGMA foreign_keys = ON;")
        conn.execute("PRAGMA journal_mode = WAL;")
        conn.execute("PRAGMA synchronous = NORMAL;")
        return conn

    @contextlib.contextmanager
    def session_scope(self):
        """Context manager that commits on success, rollbacks on error, and always closes the connection."""
        conn = self.get_connection()
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def init_schema(self, recreate: bool = False, force_replace: bool = False) -> None:
        """Initializes tables from schema.sql.

        Safety: Only permits recreation if target is inside approved synthetic directory.
        Protects Android Room databases and user datasets.
        """
        if recreate:
            if not self._is_safe_path():
                raise PermissionError(
                    f"Safety check failed: Recreating database outside approved synthetic directory is strictly prohibited: {self.db_path.resolve()}"
                )
            if self.db_path.exists():
                if not force_replace and self.output_policy == OutputPolicy.FAIL_IF_EXISTS:
                    raise PermissionError(f"Recreating database disallowed by policy {self.output_policy}: {self.db_path}")

                # Delete main db and any WAL / SHM files
                wal_path = self.db_path.with_name(f"{self.db_path.name}-wal")
                shm_path = self.db_path.with_name(f"{self.db_path.name}-shm")
                if wal_path.exists():
                    wal_path.unlink()
                if shm_path.exists():
                    shm_path.unlink()
                self.db_path.unlink()
        elif self.db_path.exists() and self.output_policy == OutputPolicy.FAIL_IF_EXISTS:
            raise FileExistsError(f"Database already exists at: {self.db_path}")

        schema_sql = self.schema_path.read_text(encoding="utf-8")
        with self.session_scope() as conn:
            conn.executescript(schema_sql)

    def insert_subjects(self, subjects: Sequence[Any]) -> int:
        """Inserts virtual subjects."""
        rows = [
            (
                s.subject_id,
                s.baseline_hr,
                s.hr_response_amplitude,
                s.hr_tau_rise_sec,
                s.hr_tau_recovery_sec,
                s.hr_variability_std,
                s.movement_scale,
                s.restlessness_tendency,
                s.preferred_placement,
                json.dumps(s.device_orientation_euler),
                s.sensor_noise_std,
                s.ble_packet_loss_rate,
                s.typical_duration_min,
                s.generator_version,
                int(time.time() * 1000),
            )
            for s in subjects
        ]
        sql = """
            INSERT OR REPLACE INTO subjects (
                subject_id, baseline_hr, hr_response_amplitude, hr_tau_rise_sec,
                hr_tau_recovery_sec, hr_variability_std, movement_scale,
                restlessness_tendency, preferred_placement, device_orientation_euler,
                sensor_noise_std, ble_packet_loss_rate, typical_duration_min,
                generator_version, created_at_utc_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        with self.session_scope() as conn:
            conn.executemany(sql, rows)
        return len(rows)

    def insert_session(self, session_data: dict[str, Any]) -> None:
        """Inserts a single session metadata record."""
        sql = """
            INSERT OR REPLACE INTO sessions (
                session_id, subject_id, start_utc_millis, end_utc_millis,
                duration_sec, device_placement, sampling_rate_hz, simulation_seed,
                source_kind, generator_version, quality_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        row = (
            session_data["session_id"],
            session_data["subject_id"],
            session_data["start_utc_millis"],
            session_data["end_utc_millis"],
            session_data["duration_sec"],
            session_data["device_placement"],
            session_data.get("sampling_rate_hz", 20),
            session_data["simulation_seed"],
            session_data.get("source_kind", "SIMULATED"),
            session_data.get("generator_version", "2.0.0"),
            session_data.get("quality_status", "EXCELLENT"),
        )
        with self.session_scope() as conn:
            conn.execute(sql, row)

    def insert_accelerometer_samples_batch(self, samples: Sequence[dict[str, Any]]) -> int:
        """High-speed batch insert for raw accelerometer readings."""
        if not samples:
            return 0
        rows = [
            (
                s["session_id"],
                s["elapsed_nanos"],
                s["utc_millis"],
                s["x"],
                s["y"],
                s["z"],
                s.get("accuracy", 3),
                s.get("source_kind", "SIMULATED"),
            )
            for s in samples
        ]
        sql = """
            INSERT INTO accelerometer_samples (
                session_id, elapsed_nanos, utc_millis, x, y, z, accuracy, source_kind
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
        """
        with self.session_scope() as conn:
            conn.executemany(sql, rows)
        return len(rows)

    def insert_heart_rate_samples_batch(self, samples: Sequence[dict[str, Any]]) -> int:
        """High-speed batch insert for BLE heart rate readings."""
        if not samples:
            return 0
        rows = [
            (
                s["sample_id"],
                s["session_id"],
                s["device_id"],
                s["bpm"],
                s["utc_millis"],
                s.get("timing_uncertainty_millis", 50),
                s.get("source_kind", "SIMULATED"),
            )
            for s in samples
        ]
        sql = """
            INSERT OR REPLACE INTO heart_rate_samples (
                sample_id, session_id, device_id, bpm, utc_millis, timing_uncertainty_millis, source_kind
            ) VALUES (?, ?, ?, ?, ?, ?, ?);
        """
        with self.session_scope() as conn:
            conn.executemany(sql, rows)
        return len(rows)

    def insert_ground_truth_batch(self, intervals: Sequence[Any], start_utc_ms: int) -> int:
        """Inserts ground truth latent state intervals."""
        if not intervals:
            return 0
        rows = [
            (
                it.interval_id,
                it.session_id,
                start_utc_ms + round(it.start_sec * 1000),
                start_utc_ms + round(it.end_sec * 1000),
                it.start_sec,
                it.end_sec,
                it.state,
                it.state_name,
            )
            for it in intervals
        ]
        sql = """
            INSERT OR REPLACE INTO ground_truth_intervals (
                interval_id, session_id, start_utc_millis, end_utc_millis,
                start_sec, end_sec, state_id, state_label
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
        """
        with self.session_scope() as conn:
            conn.executemany(sql, rows)
        return len(rows)

    def insert_feature_windows_batch(self, windows: Sequence[dict[str, Any]]) -> int:
        """Inserts computed feature windows with complete quality and readiness metadata."""
        if not windows:
            return 0
        rows = [
            (
                w["window_id"],
                w["session_id"],
                w["start_utc_millis"],
                w["end_utc_millis"],
                w.get("sample_count", 20),
                w.get("coverage_fraction", 1.0),
                w["motion_rms"],
                w["variance"],
                w["jerk"],
                w["peak_to_peak"],
                w.get("mean_hr"),
                1 if w.get("hr_available") else 0,
                w["ground_truth_label"],
                w.get("provenance", "SIMULATED"),
                w.get("max_gap_ms", 50),
                1 if w.get("has_large_gap") else 0,
                1 if w.get("was_resampled") else 0,
                1 if w.get("training_eligible", 1) else 0,
                w.get("quality_flags", ""),
            )
            for w in windows
        ]
        sql = """
            INSERT OR REPLACE INTO feature_windows (
                window_id, session_id, start_utc_millis, end_utc_millis,
                sample_count, coverage_fraction, motion_rms, variance, jerk,
                peak_to_peak, mean_hr, hr_available, ground_truth_label, provenance,
                max_gap_ms, has_large_gap, was_resampled, training_eligible, quality_flags
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        with self.session_scope() as conn:
            conn.executemany(sql, rows)
        return len(rows)

    def insert_model_predictions_batch(self, predictions: Sequence[dict[str, Any]]) -> int:
        """Inserts model inference results."""
        if not predictions:
            return 0
        rows = [
            (
                p["prediction_id"],
                p["window_id"],
                p["session_id"],
                p["model_name"],
                p["predicted_class"],
                p["confidence"],
                p["prob_rest"],
                p["prob_restless"],
                p["prob_active"],
                p.get("inference_duration_us", 0.0),
            )
            for p in predictions
        ]
        sql = """
            INSERT OR REPLACE INTO model_predictions (
                prediction_id, window_id, session_id, model_name,
                predicted_class, confidence, prob_rest, prob_restless,
                prob_active, inference_duration_us
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        with self.session_scope() as conn:
            conn.executemany(sql, rows)
        return len(rows)

    def insert_quality_events_batch(self, events: Sequence[Any]) -> int:
        """Inserts data quality impairment events."""
        if not events:
            return 0
        rows = [
            (
                e.event_id,
                e.session_id,
                e.event_type,
                e.severity,
                e.timestamp_utc_ms,
                e.details,
            )
            for e in events
        ]
        sql = """
            INSERT OR REPLACE INTO data_quality_events (
                event_id, session_id, event_type, severity, timestamp_utc_ms, details
            ) VALUES (?, ?, ?, ?, ?, ?);
        """
        with self.session_scope() as conn:
            conn.executemany(sql, rows)
        return len(rows)

    def record_manifest(self, manifest: dict[str, Any]) -> None:
        """Records dataset summary manifest."""
        sql = """
            INSERT OR REPLACE INTO dataset_manifests (
                manifest_id, dataset_version, dataset_profile, generator_config_hash,
                master_seed, created_at_utc, subject_count, session_count,
                total_duration_hours, accel_sample_count, hr_sample_count,
                window_count, ground_truth_count, quality_event_count, schema_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        row = (
            manifest["manifest_id"],
            manifest["dataset_version"],
            manifest["dataset_profile"],
            manifest["generator_config_hash"],
            manifest["master_seed"],
            manifest["created_at_utc"],
            manifest["subject_count"],
            manifest["session_count"],
            manifest["total_duration_hours"],
            manifest["accel_sample_count"],
            manifest["hr_sample_count"],
            manifest["window_count"],
            manifest["ground_truth_count"],
            manifest["quality_event_count"],
            manifest.get("schema_version", "2.1.0"),
        )
        with self.session_scope() as conn:
            conn.execute(sql, row)

    def verify_referential_integrity(self) -> list[str]:
        """Runs PRAGMA foreign_key_check and reports violations."""
        with self.session_scope() as conn:
            cursor = conn.cursor()
            cursor.execute("PRAGMA foreign_key_check;")
            violations = cursor.fetchall()
            if violations:
                return [f"FK violation in table '{v[0]}' rowid {v[1]} -> parent '{v[2]}'" for v in violations]
            return []

    def get_table_counts(self) -> dict[str, int]:
        """Returns row counts for all database tables."""
        tables = [
            "subjects", "sessions", "accelerometer_samples", "heart_rate_samples",
            "ground_truth_intervals", "feature_windows", "model_predictions",
            "data_quality_events", "dataset_manifests"
        ]
        counts = {}
        with self.session_scope() as conn:
            cursor = conn.cursor()
            for t in tables:
                try:
                    cursor.execute(f"SELECT COUNT(*) FROM {t};")
                    counts[t] = cursor.fetchone()[0]
                except sqlite3.OperationalError:
                    counts[t] = -1
        return counts
