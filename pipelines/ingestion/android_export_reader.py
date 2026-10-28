"""Ingestion module for loading and parsing Android SleepSense exports."""

from __future__ import annotations

import json
from pathlib import Path

import polars as pl

from pipelines.validation.schemas import HeartRateSchema, RawAccelerometerSchema


class AndroidExportReader:
    """Reads and parses sensor recordings exported from Android SleepSense."""

    @staticmethod
    def read_json_export(file_path: Path | str) -> tuple[pl.DataFrame, pl.DataFrame]:
        """Loads a session JSON bundle containing raw samples and heart rate records."""
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Export file not found: {path}")

        data = json.loads(path.read_text(encoding="utf-8"))
        session_id = data.get("sessionId", "unknown-session")

        raw_samples = data.get("samples", [])
        hr_samples = data.get("heartRateSamples", [])

        # Process raw accelerometer samples
        if raw_samples:
            accel_df = pl.DataFrame([
                {
                    "session_id": session_id,
                    "source_id": s.get("sourceId", "phone_accelerometer"),
                    "elapsed_nanos": int(s.get("elapsedRealtimeNanos", 0)),
                    "utc_millis": int(s.get("utcTimestampMillis", 0)),
                    "x": float(s.get("x", 0.0)),
                    "y": float(s.get("y", 0.0)),
                    "z": float(s.get("z", 0.0)),
                    "accuracy": int(s.get("sensorAccuracy", 3)),
                    "source_kind": str(s.get("sourceKind", "PHONE_REAL")),
                }
                for s in raw_samples
            ]).cast({
                "elapsed_nanos": pl.Int64,
                "utc_millis": pl.Int64,
                "x": pl.Float32,
                "y": pl.Float32,
                "z": pl.Float32,
                "accuracy": pl.Int32,
            })
        else:
            accel_df = pl.DataFrame(schema={
                "session_id": pl.String,
                "source_id": pl.String,
                "elapsed_nanos": pl.Int64,
                "utc_millis": pl.Int64,
                "x": pl.Float32,
                "y": pl.Float32,
                "z": pl.Float32,
                "accuracy": pl.Int32,
                "source_kind": pl.String,
            })

        # Process heart rate samples
        if hr_samples:
            hr_df = pl.DataFrame([
                {
                    "session_id": session_id,
                    "device_id": h.get("deviceId", "heart_rate_monitor"),
                    "bpm": int(h.get("bpm", 0)),
                    "utc_millis": int(h.get("utcTimestampMillis", 0)),
                    "source_kind": str(h.get("sourceKind", "BLE_REAL")),
                }
                for h in hr_samples
            ]).cast({
                "bpm": pl.Int32,
                "utc_millis": pl.Int64,
            })
        else:
            hr_df = pl.DataFrame(schema={
                "session_id": pl.String,
                "device_id": pl.String,
                "bpm": pl.Int32,
                "utc_millis": pl.Int64,
                "source_kind": pl.String,
            })

        # Validate with Pandera
        if len(accel_df) > 0:
            accel_df = RawAccelerometerSchema.validate(accel_df)
        if len(hr_df) > 0:
            hr_df = HeartRateSchema.validate(hr_df)

        return accel_df, hr_df

    @staticmethod
    def read_csv_export(file_path: Path | str) -> pl.DataFrame:
        """Loads accelerometer samples from CSV export."""
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Export CSV not found: {path}")

        df = pl.read_csv(path)
        return RawAccelerometerSchema.validate(df)
