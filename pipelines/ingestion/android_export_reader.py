"""Ingestion module for loading and parsing Android SleepSense exports."""

from __future__ import annotations

import io
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
                    "elapsed_nanos": int(s.get("elapsedRealtimeNanos", s.get("elapsed_nanos", 0))),
                    "utc_millis": int(s.get("utcTimestampMillis", s.get("utc_millis", 0))),
                    "x": float(s.get("x", s.get("accel_x", 0.0))),
                    "y": float(s.get("y", s.get("accel_y", 0.0))),
                    "z": float(s.get("z", s.get("accel_z", 0.0))),
                    "accuracy": int(s.get("sensorAccuracy", s.get("accuracy", 3))),
                    "source_kind": str(s.get("sourceKind", s.get("source_kind", "PHONE_REAL"))),
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
                    "device_id": h.get("deviceId", h.get("device_id", "heart_rate_monitor")),
                    "bpm": int(h.get("bpm", 0)),
                    "utc_millis": int(h.get("utcTimestampMillis", h.get("utc_millis", 0))),
                    "source_kind": str(h.get("sourceKind", h.get("source_kind", "BLE_REAL"))),
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
        """Loads accelerometer samples from Android CSV export, supporting metadata headers and column mapping."""
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Export CSV not found: {path}")

        lines = path.read_text(encoding="utf-8").splitlines()
        session_id = path.stem
        csv_lines = []

        # Parse # metadata comments
        for line in lines:
            trimmed = line.strip()
            if not trimmed:
                continue
            if trimmed.startswith("#"):
                meta_content = trimmed.lstrip("#").strip()
                if ":" in meta_content:
                    key, val = meta_content.split(":", 1)
                    if key.strip().lower() == "sessionid":
                        session_id = val.strip()
            else:
                csv_lines.append(trimmed)

        if not csv_lines:
            return pl.DataFrame(schema={
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

        # Read CSV data block
        df = pl.read_csv(io.StringIO("\n".join(csv_lines)))

        # Rename Android-specific column names
        rename_map = {}
        for col in df.columns:
            lower = col.lower()
            if lower in ("timestamp_utc_ms", "utc_timestamp_millis", "timestamp"):
                rename_map[col] = "utc_millis"
            elif lower in ("elapsed_realtime_ns", "elapsed_realtime_nanos", "elapsed_realtime"):
                rename_map[col] = "elapsed_nanos"
            elif lower in ("accel_x",):
                rename_map[col] = "x"
            elif lower in ("accel_y",):
                rename_map[col] = "y"
            elif lower in ("accel_z",):
                rename_map[col] = "z"

        if rename_map:
            df = df.rename(rename_map)

        # Ensure required schema columns exist
        if "session_id" not in df.columns:
            df = df.with_columns(pl.lit(session_id).alias("session_id"))
        if "source_id" not in df.columns:
            df = df.with_columns(pl.lit("phone_accelerometer").alias("source_id"))
        if "accuracy" not in df.columns:
            df = df.with_columns(pl.lit(3).alias("accuracy"))
        if "source_kind" not in df.columns:
            df = df.with_columns(pl.lit("PHONE_REAL").alias("source_kind"))

        # Select only expected columns and cast
        df = df.select([
            "session_id",
            "source_id",
            "elapsed_nanos",
            "utc_millis",
            "x",
            "y",
            "z",
            "accuracy",
            "source_kind",
        ]).cast({
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

        return RawAccelerometerSchema.validate(df)

    # Backward-compatible method aliases
    read_session_json = read_json_export
    read_accelerometer_csv = read_csv_export
