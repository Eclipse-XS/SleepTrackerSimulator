"""Data quality validation schemas for SleepSense sensor data using Pandera.

Canonical vocabularies mirror the Kotlin contract (see docs/datascience/ANDROID_PYTHON_DATA_CONTRACT.md).
"""

from __future__ import annotations

import pandera.polars as pa
import polars as pl

# --- Provenance (Kotlin `SourceKind`) -------------------------------------------------------------
CANONICAL_SOURCE_KINDS: tuple[str, ...] = (
    "PHONE_REAL",
    "BLE_REAL",
    "BLE_UNVERIFIED",
    "WEBSOCKET_SIMULATED",
    "SIMULATED",
    "REPLAY",
    "IMPORTED",
)
# Historical values that older exports/tests used. They stay readable but are never coerced to a
# canonical value: BLE_SIMULATED is *not* BLE_REAL, DEMO is *not* a genuine sensor recording.
LEGACY_SOURCE_KINDS: tuple[str, ...] = ("BLE_SIMULATED", "DEMO")
READABLE_SOURCE_KINDS: tuple[str, ...] = CANONICAL_SOURCE_KINDS + LEGACY_SOURCE_KINDS
# Only these may enter "real data" training without a separate human review.
REAL_SENSOR_SOURCE_KINDS: tuple[str, ...] = ("PHONE_REAL", "BLE_REAL")

# --- Labels ---------------------------------------------------------------------------------------
MOTION_CLASS_LABELS: tuple[str, ...] = ("REST", "RESTLESS", "ACTIVE")
UNLABELED = "UNLABELED"
# Original experimental annotations (Kotlin `GroundTruthActivity`) - NOT the ML vocabulary.
GROUND_TRUTH_ACTIVITIES: tuple[str, ...] = ("RESTING", "READING", "OUT_OF_BED", "WALKING")


def normalize_source_kind(value: object, field: str = "source_kind") -> str:
    """Return a readable source kind unchanged (case-insensitive), or raise with an informative error.

    Unknown values are never defaulted to PHONE_REAL and never coerced to another kind.
    """
    text = str(value).strip().upper()
    if text in READABLE_SOURCE_KINDS:
        return text
    raise ValueError(
        f"Unsupported {field} {value!r}. Canonical: {list(CANONICAL_SOURCE_KINDS)}; "
        f"legacy-readable: {list(LEGACY_SOURCE_KINDS)}."
    )


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
        "source_kind": pa.Column(pl.String, checks=[pa.Check.isin(list(READABLE_SOURCE_KINDS))]),
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
        "source_kind": pa.Column(pl.String, checks=[pa.Check.isin(list(READABLE_SOURCE_KINDS))]),
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
        # Number of *raw observed* samples that fell into the window (19 stays 19 after resampling).
        "sample_count": pa.Column(pl.Int32, checks=[pa.Check.greater_than_or_equal_to(0)]),
        # Temporal (event-time bin) coverage computed during window construction.
        "coverage_fraction": pa.Column(pl.Float32, checks=[pa.Check.greater_than_or_equal_to(0.0), pa.Check.less_than_or_equal_to(1.0)]),
        "motion_rms": pa.Column(pl.Float32, pa.Check.greater_than_or_equal_to(0.0)),
        "variance": pa.Column(pl.Float32, pa.Check.greater_than_or_equal_to(0.0)),
        "jerk": pa.Column(pl.Float32, pa.Check.greater_than_or_equal_to(0.0)),
        "mean_hr": pa.Column(pl.Float32, nullable=True),
        "hr_available": pa.Column(pl.Boolean),
        # `label` is the supervised ML target (motion_class_label); UNLABELED/UNCERTAIN are never trained on.
        "label": pa.Column(pl.String, checks=[pa.Check.isin([*MOTION_CLASS_LABELS, "UNCERTAIN", UNLABELED])]),
        "provenance": pa.Column(pl.String, nullable=False),
        # --- Optional quality / lineage columns (present for Android-export windows) ---
        "raw_sample_count": pa.Column(pl.Int32, checks=[pa.Check.greater_than_or_equal_to(0)], required=False),
        "model_tensor_sample_count": pa.Column(pl.Int32, checks=[pa.Check.equal_to(20)], required=False),
        "ground_truth_activity": pa.Column(pl.String, nullable=True, required=False),
        "has_large_gap": pa.Column(pl.Boolean, required=False),
        "max_gap_ms": pa.Column(pl.Int64, required=False),
        "was_resampled": pa.Column(pl.Boolean, required=False),
        "training_eligible": pa.Column(pl.Boolean, required=False),
        "quality_flags": pa.Column(pl.String, nullable=True, required=False),
        "prediction_cutoff_utc": pa.Column(pl.Int64, nullable=True, required=False),
        "hr_source": pa.Column(pl.String, nullable=True, required=False),
        "feature_contract": pa.Column(pl.String, nullable=True, required=False),
    },
    coerce=True,
    strict=False,
)
