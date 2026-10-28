"""Cleaning and quality verification for sensor streams using Polars."""

from __future__ import annotations

import polars as pl


class DataCleaner:
    """Performs deterministic cleaning and anomaly filtering on raw sensor samples."""

    @staticmethod
    def clean_accelerometer(
        df: pl.DataFrame,
        nominal_frequency_hz: int = 20,
    ) -> pl.DataFrame:
        """Deduplicates, sorts, filters non-finite values, and audits sampling intervals."""
        if df.is_empty():
            return df

        # 1. Drop any rows with nulls in critical axes
        cleaned = df.filter(
            pl.col("x").is_not_null() &
            pl.col("y").is_not_null() &
            pl.col("z").is_not_null() &
            pl.col("elapsed_nanos").is_not_null() &
            pl.col("utc_millis").is_not_null() &
            pl.col("x").is_finite() &
            pl.col("y").is_finite() &
            pl.col("z").is_finite()
        )

        # 2. Deduplicate exact timestamps per session
        cleaned = cleaned.unique(subset=["session_id", "elapsed_nanos"], keep="first")

        # 3. Sort chronologically
        cleaned = cleaned.sort(by=["session_id", "elapsed_nanos"])

        return cleaned

    @staticmethod
    def audit_accelerometer_sampling(
        df: pl.DataFrame,
        nominal_frequency_hz: int = 20,
    ) -> dict[str, int | float]:
        """Audits sampling intervals, jitter, and gaps."""
        if df.is_empty():
            return {"total_samples": 0, "gaps_detected": 0}

        nominal_interval_ns = int(1_000_000_000 / nominal_frequency_hz)
        gap_threshold_ns = nominal_interval_ns * 5

        intervals = (
            df.with_columns(
                (pl.col("elapsed_nanos") - pl.col("elapsed_nanos").shift(1).over("session_id"))
                .fill_null(nominal_interval_ns)
                .alias("interval_ns")
            )
        )
        gaps_count = intervals.filter(pl.col("interval_ns") > gap_threshold_ns).height

        return {
            "total_samples": len(df),
            "gaps_detected": gaps_count,
            "nominal_interval_ms": nominal_interval_ns / 1_000_000,
        }

    @staticmethod
    def clean_heart_rate(df: pl.DataFrame) -> pl.DataFrame:
        """Cleans heart rate samples: removes nulls, out-of-range BPM (<30 or >240), duplicates, and sorts."""
        if df.is_empty():
            return df

        cleaned = df.filter(
            pl.col("bpm").is_not_null() &
            pl.col("utc_millis").is_not_null() &
            (pl.col("bpm") >= 30) &
            (pl.col("bpm") <= 240)
        )

        cleaned = cleaned.unique(subset=["session_id", "utc_millis"], keep="first")
        cleaned = cleaned.sort(by=["session_id", "utc_millis"])
        return cleaned
