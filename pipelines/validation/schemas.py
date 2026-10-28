"""Data quality validation schemas for SleepSense sensor data using Pandera."""

from __future__ import annotations

import pandera.polars as pa
import polars as pl

RawAccelerometerSchema = pa.DataFrameSchema(
    columns={
        "session_id": pa.Column(pl.String, nullable=False),
        "source_id": pa.Column(pl.String, nullable=False),
        "elapsed_nanos": pa.Column(pl.Int64, pa.Check.greater_than_or_equal_to(0)),
        "utc_millis": pa.Column(pl.Int64, pa.Check.greater_than_or_equal_to(0)),
        "x": pa.Column(
            pl.Float32,
            checks=[
                pa.Check.greater_than_or_equal_to(-196.0),
                pa.Check.less_than_or_equal_to(196.0),
            ],
            nullable=False,
        ),
        "y": pa.Column(
            pl.Float32,
            checks=[
                pa.Check.greater_than_or_equal_to(-196.0),
                pa.Check.less_than_or_equal_to(196.0),
            ],
            nullable=False,
        ),
        "z": pa.Column(
            pl.Float32,
            checks=[
                pa.Check.greater_than_or_equal_to(-196.0),
                pa.Check.less_than_or_equal_to(196.0),
            ],
            nullable=False,
        ),
        "accuracy": pa.Column(pl.Int32, checks=[pa.Check.greater_than_or_equal_to(0), pa.Check.less_than_or_equal_to(3)]),
        "source_kind": pa.Column(pl.String, checks=[pa.Check.isin(["PHONE_REAL", "BLE_REAL", "BLE_SIMULATED", "DEMO", "IMPORTED"])]),
    },
    coerce=True,
    strict=False,
)

HeartRateSchema = pa.DataFrameSchema(
    columns={
        "session_id": pa.Column(pl.String, nullable=False),
        "device_id": pa.Column(pl.String, nullable=False),
        "bpm": pa.Column(pl.Int32, checks=[pa.Check.greater_than_or_equal_to(30), pa.Check.less_than_or_equal_to(240)]),
        "utc_millis": pa.Column(pl.Int64, pa.Check.greater_than_or_equal_to(0)),
        "source_kind": pa.Column(pl.String, checks=[pa.Check.isin(["PHONE_REAL", "BLE_REAL", "BLE_SIMULATED", "DEMO", "IMPORTED"])]),
    },
    coerce=True,
    strict=False,
)

WindowFeaturesSchema = pa.DataFrameSchema(
    columns={
        "window_id": pa.Column(pl.String, nullable=False),
        "session_id": pa.Column(pl.String, nullable=False),
        "start_utc_millis": pa.Column(pl.Int64, pa.Check.greater_than_or_equal_to(0)),
        "end_utc_millis": pa.Column(pl.Int64, pa.Check.greater_than_or_equal_to(0)),
        "sample_count": pa.Column(pl.Int32, checks=[pa.Check.equal_to(20)]),
        "coverage_fraction": pa.Column(pl.Float32, checks=[pa.Check.greater_than_or_equal_to(0.0), pa.Check.less_than_or_equal_to(1.0)]),
        "motion_rms": pa.Column(pl.Float32, pa.Check.greater_than_or_equal_to(0.0)),
        "variance": pa.Column(pl.Float32, pa.Check.greater_than_or_equal_to(0.0)),
        "jerk": pa.Column(pl.Float32, pa.Check.greater_than_or_equal_to(0.0)),
        "mean_hr": pa.Column(pl.Float32, nullable=True),
        "hr_available": pa.Column(pl.Boolean),
        "label": pa.Column(pl.String, checks=[pa.Check.isin(["REST", "RESTLESS", "ACTIVE", "UNCERTAIN"])]),
        "provenance": pa.Column(pl.String, nullable=False),
    },
    coerce=True,
    strict=False,
)
