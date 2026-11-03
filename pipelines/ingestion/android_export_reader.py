"""Ingestion module for loading and parsing Android SleepSense exports.

Processing order (DE-004): parse raw records -> keep original validity/rejection info -> separate accepted
from rejected rows -> audit rejections -> (caller) clean + validate accepted rows.

Original annotations are preserved exactly:
* ``ground_truth_activity`` - experimental annotation (RESTING/READING/OUT_OF_BED/WALKING), never rewritten.
* ``predicted_motion_class`` - model output, kept for analysis only, never used as a label.
* ``motion_class_label`` - supervised ML target (REST/RESTLESS/ACTIVE). Defaults to ``UNLABELED``; it is
  only populated by an explicit ``motion_class_label`` field in the export or by an *opt-in, versioned*
  :class:`ActivityToMotionMapping`.

Fallback validity policy (exports without ``is_valid``, e.g. legacy v2.0 JSON): a record is valid iff its
timestamps are present and non-negative, x/y/z are finite and ``|axis| <= FALLBACK_MAX_ABS_AXIS`` (the same
physical bound the Android DatasetRecordingController applies). Missing numbers are never turned into zeros.
"""

from __future__ import annotations

import csv
import io
import json
import math
from collections import Counter
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import polars as pl

from pipelines.validation.schemas import (
    MOTION_CLASS_LABELS,
    UNLABELED,
    HeartRateSchema,
    RawAccelerometerSchema,
    normalize_source_kind,
)

FALLBACK_MAX_ABS_AXIS = 50.0
FALLBACK_POLICY_NAME = "fallback_range_policy_v1"
ANDROID_FLAG_POLICY_NAME = "android_is_valid_flag_with_defensive_checks"

_ACCEL_SCHEMA: dict[str, Any] = {
    "session_id": pl.String,
    "source_id": pl.String,
    "elapsed_nanos": pl.Int64,
    "utc_millis": pl.Int64,
    "x": pl.Float32,
    "y": pl.Float32,
    "z": pl.Float32,
    "accuracy": pl.Int32,
    "source_kind": pl.String,
    "ground_truth_activity": pl.String,
    "predicted_motion_class": pl.String,
    "motion_class_label": pl.String,
    "experiment_type": pl.String,
    "placement": pl.String,
}
_REJECTED_SCHEMA: dict[str, Any] = {**_ACCEL_SCHEMA, "rejection_reason": pl.String, "row_index": pl.Int64}
_HR_SCHEMA: dict[str, Any] = {
    "session_id": pl.String,
    "device_id": pl.String,
    "bpm": pl.Int32,
    "utc_millis": pl.Int64,
    "source_kind": pl.String,
}

_CSV_ALIASES = {
    "utc_millis": ("timestamp_utc_ms", "utc_timestamp_millis", "timestamp", "utc_millis"),
    "elapsed_nanos": ("elapsed_realtime_ns", "elapsed_realtime_nanos", "elapsed_realtime", "elapsed_nanos"),
    "x": ("accel_x", "x"),
    "y": ("accel_y", "y"),
    "z": ("accel_z", "z"),
    "is_valid": ("is_valid",),
    "rejection_reason": ("rejection_reason",),
    "ground_truth_activity": ("ground_truth_label", "ground_truth_activity"),
    "predicted_motion_class": ("predicted_motion_class",),
    "motion_class_label": ("motion_class_label",),
    "source_kind": ("source_kind",),
    "accuracy": ("accuracy", "sensor_accuracy"),
    "session_id": ("session_id",),
    "source_id": ("source_id",),
}


@dataclass(frozen=True)
class ActivityToMotionMapping:
    """Opt-in, versioned mapping from experimental annotations to supervised motion classes."""

    version: str
    mapping: Mapping[str, str]

    def __post_init__(self) -> None:
        bad = {k: v for k, v in self.mapping.items() if v not in MOTION_CLASS_LABELS}
        if bad:
            raise ValueError(f"Mapping {self.version!r} targets non-ML labels: {bad}")


@dataclass
class IngestedExport:
    """Result of ingesting one export: accepted/rejected rows, HR, source metadata and audit counts."""

    accepted: pl.DataFrame
    rejected: pl.DataFrame
    heart_rate: pl.DataFrame
    metadata: dict[str, Any]
    audit: dict[str, Any] = field(default_factory=dict)


def _num(raw: Any) -> float | None:
    """Parse a number; missing/malformed -> None (never 0)."""
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        return float(raw)
    text = str(raw).strip()
    if text == "" or text.lower() in {"null", "none"}:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def _int(raw: Any) -> int | None:
    value = _num(raw)
    if value is None or not math.isfinite(value):
        return None
    return int(value)


def _bool(raw: Any) -> bool | None:
    if raw is None:
        return None
    if isinstance(raw, bool):
        return raw
    text = str(raw).strip().lower()
    if text in {"true", "1"}:
        return True
    if text in {"false", "0"}:
        return False
    return None


def _text(raw: Any) -> str | None:
    if raw is None:
        return None
    text = str(raw).strip()
    return text or None


def _sample_source_kind(sample: Mapping[str, Any]) -> str:
    """Explicit source kinds are validated (never coerced); an absent field means legacy PHONE_REAL."""
    for key in ("sourceKind", "source_kind"):
        if key in sample:
            return normalize_source_kind(sample[key], f"samples[].{key}")
    return "PHONE_REAL"


def _classify(rec: dict[str, Any]) -> tuple[bool, str | None]:
    """Decide validity of a parsed record. Returns (is_valid, rejection_reason)."""
    defensive: str | None = None
    if rec["elapsed_nanos"] is None or rec["utc_millis"] is None:
        defensive = "MALFORMED_TIMESTAMP"
    elif rec["elapsed_nanos"] < 0 or rec["utc_millis"] < 0:
        defensive = "NEGATIVE_TIMESTAMP"
    else:
        axes = (rec["x"], rec["y"], rec["z"])
        if any(a is None or not math.isfinite(a) for a in axes):
            defensive = "NON_FINITE_VALUE"
        elif any(abs(a) > FALLBACK_MAX_ABS_AXIS for a in axes):
            defensive = "OUT_OF_RANGE_EXCESSIVE_G"

    flag = rec["is_valid_source"]
    if flag is False:
        return False, rec["rejection_reason_source"] or defensive or "SOURCE_FLAGGED_INVALID"
    if defensive is not None:
        return False, defensive
    return True, None


def _resolve_motion_label(rec: dict[str, Any], mapping: ActivityToMotionMapping | None) -> str:
    explicit = rec["motion_class_label_source"]
    if explicit is not None:
        if explicit not in MOTION_CLASS_LABELS:
            return UNLABELED
        return explicit
    activity = rec["ground_truth_activity"]
    if mapping is not None and activity is not None and activity in mapping.mapping:
        return mapping.mapping[activity]
    return UNLABELED


def _finalize(
    records: list[dict[str, Any]],
    hr_records: list[dict[str, Any]],
    metadata: dict[str, Any],
    mapping: ActivityToMotionMapping | None,
) -> IngestedExport:
    has_flag = any(r["is_valid_source"] is not None for r in records)
    accepted_rows: list[dict[str, Any]] = []
    rejected_rows: list[dict[str, Any]] = []
    for idx, rec in enumerate(records):
        valid, reason = _classify(rec)
        row = {k: rec[k] for k in _ACCEL_SCHEMA if k in rec and k != "motion_class_label"}
        row["motion_class_label"] = _resolve_motion_label(rec, mapping)
        if valid:
            accepted_rows.append(row)
        else:
            row["rejection_reason"] = reason
            row["row_index"] = idx
            rejected_rows.append(row)

    accepted = pl.DataFrame(accepted_rows, schema=_ACCEL_SCHEMA, strict=False)
    rejected = pl.DataFrame(rejected_rows, schema=_REJECTED_SCHEMA, strict=False)
    hr_df = pl.DataFrame(hr_records, schema=_HR_SCHEMA, strict=False)

    reasons = Counter(r["rejection_reason"] for r in rejected_rows)
    metadata = {
        **metadata,
        "validity_policy": ANDROID_FLAG_POLICY_NAME if has_flag else FALLBACK_POLICY_NAME,
        "activity_mapping_version": mapping.version if mapping else None,
    }
    audit = {
        "raw_records": len(records),
        "accepted_records": len(accepted_rows),
        "rejected_records": len(rejected_rows),
        "rejected_by_reason": dict(sorted(reasons.items())),
        "heart_rate_records": len(hr_records),
        "validity_policy": metadata["validity_policy"],
    }
    return IngestedExport(accepted, rejected, hr_df, metadata, audit)


class AndroidExportReader:
    """Reads and parses sensor recordings exported from Android SleepSense."""

    # ------------------------------------------------------------------ JSON
    @staticmethod
    def ingest_json_export(
        file_path: Path | str,
        activity_mapping: ActivityToMotionMapping | None = None,
    ) -> IngestedExport:
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Export file not found: {path}")

        data = json.loads(path.read_text(encoding="utf-8"))
        session_id = data.get("sessionId", "unknown-session")
        metadata = {
            "session_id": session_id,
            "experiment_type": _text(data.get("experimentType")),
            "placement": _text(data.get("placement")),
            "ground_truth_activity": _text(data.get("groundTruthActivity")),
            "device_model": _text(data.get("deviceModel")),
            "schema_version": _text(data.get("schemaVersion")) or "2.0",
            "source_format": "json",
        }

        records: list[dict[str, Any]] = []
        for s in data.get("samples", []):
            records.append({
                "session_id": session_id,
                "source_id": s.get("sourceId", "phone_accelerometer"),
                "elapsed_nanos": _int(s.get("elapsedRealtimeNanos", s.get("elapsed_nanos"))),
                "utc_millis": _int(s.get("utcTimestampMillis", s.get("utc_millis"))),
                "x": _num(s.get("x", s.get("accel_x"))),
                "y": _num(s.get("y", s.get("accel_y"))),
                "z": _num(s.get("z", s.get("accel_z"))),
                "accuracy": _int(s.get("sensorAccuracy", s.get("accuracy", 3))) or 0,
                "source_kind": _sample_source_kind(s),
                "ground_truth_activity": _text(s.get("groundTruthLabel", s.get("ground_truth_activity"))),
                "predicted_motion_class": _text(s.get("predictedMotionClass", s.get("predicted_motion_class"))),
                "motion_class_label_source": _text(s.get("motionClassLabel", s.get("motion_class_label"))),
                "experiment_type": metadata["experiment_type"],
                "placement": metadata["placement"],
                "is_valid_source": _bool(s.get("isValid", s.get("is_valid"))),
                "rejection_reason_source": _text(s.get("rejectionReason", s.get("rejection_reason"))),
            })

        hr_records = [
            {
                "session_id": session_id,
                "device_id": h.get("deviceId", h.get("device_id", "heart_rate_monitor")),
                "bpm": _int(h.get("bpm")),
                "utc_millis": _int(h.get("utcTimestampMillis", h.get("utc_millis"))),
                "source_kind": normalize_source_kind(
                    h.get("sourceKind", h.get("source_kind", "BLE_REAL")), "heartRateSamples[].sourceKind"
                ),
            }
            for h in data.get("heartRateSamples", [])
        ]
        return _finalize(records, hr_records, metadata, activity_mapping)

    # ------------------------------------------------------------------- CSV
    @staticmethod
    def ingest_csv_export(
        file_path: Path | str,
        activity_mapping: ActivityToMotionMapping | None = None,
    ) -> IngestedExport:
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Export CSV not found: {path}")

        meta: dict[str, str] = {}
        csv_lines: list[str] = []
        for line in path.read_text(encoding="utf-8").splitlines():
            trimmed = line.strip()
            if not trimmed:
                continue
            if trimmed.startswith("#"):
                content = trimmed.lstrip("#").strip()
                if ":" in content:
                    key, val = content.split(":", 1)
                    meta[key.strip().lower()] = val.strip()
            else:
                csv_lines.append(trimmed)

        session_id = meta.get("sessionid") or path.stem
        metadata = {
            "session_id": session_id,
            "experiment_type": meta.get("experimenttype"),
            "placement": meta.get("placement"),
            "ground_truth_activity": meta.get("groundtruthactivity"),
            "device_model": meta.get("devicemodel"),
            "schema_version": meta.get("schemaversion", "2.0"),
            "source_format": "csv",
        }
        if not csv_lines:
            return _finalize([], [], metadata, activity_mapping)

        reader = csv.reader(io.StringIO("\n".join(csv_lines)))
        header = [h.strip().lower() for h in next(reader)]
        index: dict[str, int] = {}
        for canonical, aliases in _CSV_ALIASES.items():
            for alias in aliases:
                if alias in header:
                    index[canonical] = header.index(alias)
                    break
        required = ("utc_millis", "elapsed_nanos", "x", "y", "z")
        missing = [c for c in required if c not in index]
        if missing:
            raise ValueError(f"Android CSV export is missing required columns {missing}; header={header}")

        def cell(row: list[str], key: str) -> str | None:
            i = index.get(key)
            return row[i] if i is not None and i < len(row) else None

        records: list[dict[str, Any]] = []
        for row in reader:
            raw_kind = _text(cell(row, "source_kind"))
            records.append({
                "session_id": _text(cell(row, "session_id")) or session_id,
                "source_id": _text(cell(row, "source_id")) or "phone_accelerometer",
                "elapsed_nanos": _int(cell(row, "elapsed_nanos")),
                "utc_millis": _int(cell(row, "utc_millis")),
                "x": _num(cell(row, "x")),
                "y": _num(cell(row, "y")),
                "z": _num(cell(row, "z")),
                "accuracy": _int(cell(row, "accuracy")) if cell(row, "accuracy") not in (None, "") else 3,
                "source_kind": normalize_source_kind(raw_kind, "source_kind") if raw_kind else "PHONE_REAL",
                "ground_truth_activity": _text(cell(row, "ground_truth_activity")),
                "predicted_motion_class": _text(cell(row, "predicted_motion_class")),
                "motion_class_label_source": _text(cell(row, "motion_class_label")),
                "experiment_type": metadata["experiment_type"],
                "placement": metadata["placement"],
                "is_valid_source": _bool(cell(row, "is_valid")),
                "rejection_reason_source": _text(cell(row, "rejection_reason")),
            })
        return _finalize(records, [], metadata, activity_mapping)

    # ------------------------------------------- backward-compatible API
    @staticmethod
    def read_json_export(file_path: Path | str) -> tuple[pl.DataFrame, pl.DataFrame]:
        """Accepted accelerometer rows (Pandera-validated) and cleaned HR rows."""
        from pipelines.transformation.cleaning import DataCleaner

        result = AndroidExportReader.ingest_json_export(file_path)
        accel_df = RawAccelerometerSchema.validate(result.accepted) if len(result.accepted) > 0 else result.accepted
        hr_df = DataCleaner.clean_heart_rate(result.heart_rate) if len(result.heart_rate) > 0 else result.heart_rate
        if len(hr_df) > 0:
            hr_df = HeartRateSchema.validate(hr_df)
        return accel_df, hr_df

    @staticmethod
    def read_csv_export(file_path: Path | str) -> pl.DataFrame:
        """Accepted accelerometer rows (Pandera-validated). Rejected rows are available via ``ingest_csv_export``."""
        result = AndroidExportReader.ingest_csv_export(file_path)
        return RawAccelerometerSchema.validate(result.accepted) if len(result.accepted) > 0 else result.accepted

    # Backward-compatible method aliases
    read_session_json = read_json_export
    read_accelerometer_csv = read_csv_export
