"""Dataset builder module for exporting and partitioning feature datasets to Parquet.

Implements DE-008:
- Builds into a temporary sibling staging directory instead of writing in-place with
  ``existing_data_behavior="overwrite_or_ignore"``.
- Verifies schema, row count, unique ``window_id`` values, partition columns, provenance and label
  counts on the staged Parquet files before publication.
- Requires explicit ``replace_output=True`` when replacing an existing dataset and refuses to delete
  any non-empty directory that is not a recognized SleepSense Parquet dataset.
- Keeps the previous dataset intact if staging or verification fails, and cleans up the staging
  directory without touching unrelated sibling files.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
import polars as pl
from pyarrow import parquet as pq

from pipelines.validation.schemas import MOTION_CLASS_LABELS, WindowFeaturesSchema

DATASET_MANIFEST_VERSION = "2.1"
METADATA_FILENAME = "dataset_metadata.json"
MANIFEST_FILENAME = "dataset_manifest.json"


def _is_sleepsense_dataset_dir(path: Path) -> bool:
    return (path / METADATA_FILENAME).is_file() or (path / MANIFEST_FILENAME).is_file()


def _counts_dict(df: pl.DataFrame, col: str) -> dict[str, int]:
    if col not in df.columns or len(df) == 0:
        return {}
    rows = df.group_by(col).agg(pl.len().alias("count")).to_dicts()
    return dict(sorted(((str(r[col]), int(r["count"])) for r in rows), key=lambda kv: kv[0]))


def _content_digest(df: pl.DataFrame) -> str:
    ordered = df.select(sorted(df.columns)).sort(by=["session_id", "start_utc_millis", "window_id"])
    payload = ordered.write_csv().encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


class DatasetBuilder:
    """Builds, validates, partitions, and loads Parquet datasets for SleepSense."""

    @staticmethod
    def build_parquet_dataset(
        features_df: pl.DataFrame,
        output_dir: Path | str,
        partition_cols: list[str] | None = None,
        replace_output: bool = False,
        lineage: dict[str, Any] | None = None,
        _pre_publish_hook: Callable[[Path], None] | None = None,
    ) -> Path:
        """Validates features, stages partitioned Parquet in a sibling directory, verifies and publishes."""
        out_path = Path(output_dir).resolve()
        if partition_cols is None:
            partition_cols = ["session_id", "label"]

        if out_path.exists():
            if not out_path.is_dir():
                raise ValueError(f"Output path exists and is not a directory: {out_path}")
            entries = list(out_path.iterdir())
            if entries:
                if not _is_sleepsense_dataset_dir(out_path):
                    raise ValueError(
                        f"Refusing to overwrite non-empty directory that is not a SleepSense dataset: {out_path}"
                    )
                if not replace_output:
                    raise FileExistsError(
                        f"Output dataset already exists at {out_path}. "
                        "Pass replace_output=True (or --replace-output) to replace it atomically."
                    )

        # 1. Validate schema and uniqueness before touching disk
        validated_df = WindowFeaturesSchema.validate(features_df)
        if len(validated_df) > 0:
            n_unique_ids = int(validated_df["window_id"].n_unique())
            if n_unique_ids != len(validated_df):
                raise ValueError(
                    f"Duplicate window_id detected: {len(validated_df)} rows vs {n_unique_ids} unique IDs"
                )
            validated_df = validated_df.sort(by=["session_id", "start_utc_millis", "window_id"])

        out_path.parent.mkdir(parents=True, exist_ok=True)
        token = uuid.uuid4().hex[:10]
        staging_dir = out_path.parent / f".{out_path.name}.staging-{token}"
        backup_dir = out_path.parent / f".{out_path.name}.backup-{token}"

        try:
            staging_dir.mkdir(parents=True, exist_ok=False)
            arrow_table = validated_df.to_arrow()
            pq.write_to_dataset(
                arrow_table,
                root_path=str(staging_dir),
                partition_cols=partition_cols,
                existing_data_behavior="error",
                use_dictionary=True,
                compression="SNAPPY",
            )

            # 2-5. Reload staged dataset and verify row count, unique window IDs, partitions, provenance & labels
            reloaded = DatasetBuilder.load_parquet_dataset(staging_dir) if len(validated_df) > 0 else validated_df
            if len(reloaded) != len(validated_df):
                raise RuntimeError(
                    f"Staged Parquet row-count mismatch: expected {len(validated_df)}, reloaded {len(reloaded)}"
                )
            if len(reloaded) > 0 and int(reloaded["window_id"].n_unique()) != len(validated_df):
                raise RuntimeError("Staged Parquet contains duplicate window_id values after reload")
            missing_parts = [c for c in partition_cols if c not in reloaded.columns]
            if missing_parts:
                raise RuntimeError(f"Staged Parquet is missing partition columns: {missing_parts}")

            classes_map = _counts_dict(validated_df, "label")
            prov_map = _counts_dict(validated_df, "provenance")
            if _counts_dict(reloaded, "label") != classes_map:
                raise RuntimeError("Staged Parquet label counts do not match source DataFrame")
            if _counts_dict(reloaded, "provenance") != prov_map:
                raise RuntimeError("Staged Parquet provenance counts do not match source DataFrame")

            activities_map = _counts_dict(validated_df, "ground_truth_activity")
            supervised_rows = int(
                validated_df.filter(pl.col("label").is_in(list(MOTION_CLASS_LABELS))).height
            ) if len(validated_df) > 0 else 0
            unlabeled_rows = int(len(validated_df) - supervised_rows)
            eligible_rows = (
                int(validated_df.filter(pl.col("training_eligible") == True).height)
                if "training_eligible" in validated_df.columns and len(validated_df) > 0
                else supervised_rows
            )

            total_rows = len(validated_df)
            sessions_list = sorted(validated_df["session_id"].unique().to_list()) if total_rows > 0 else []
            content_sha256 = _content_digest(validated_df) if total_rows > 0 else hashlib.sha256(b"").hexdigest()

            metadata: dict[str, Any] = {
                "manifest_version": DATASET_MANIFEST_VERSION,
                "total_windows": total_rows,
                "unique_sessions": len(sessions_list),
                "sessions": sessions_list,
                "classes": classes_map,
                "ground_truth_activities": activities_map,
                "supervised_windows": supervised_rows,
                "unlabeled_windows": unlabeled_rows,
                "training_eligible_windows": eligible_rows,
                "ineligible_windows": int(total_rows - eligible_rows),
                "provenances": prov_map,
                "partition_columns": list(partition_cols),
                "columns": sorted(validated_df.columns),
                "min_start_utc": int(validated_df["start_utc_millis"].min()) if total_rows > 0 else 0,
                "max_end_utc": int(validated_df["end_utc_millis"].max()) if total_rows > 0 else 0,
                "content_sha256": content_sha256,
                "lineage": lineage or {},
            }
            manifest = {
                **metadata,
                "generated_at_utc": datetime.now(UTC).isoformat(),
            }

            (staging_dir / METADATA_FILENAME).write_text(
                json.dumps(metadata, indent=2, sort_keys=True), encoding="utf-8"
            )
            (staging_dir / MANIFEST_FILENAME).write_text(
                json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8"
            )

            if _pre_publish_hook is not None:
                _pre_publish_hook(staging_dir)

            # 6. Atomic publication
            had_existing = out_path.exists()
            if had_existing:
                shutil.move(str(out_path), str(backup_dir))
            try:
                shutil.move(str(staging_dir), str(out_path))
            except Exception:
                if had_existing and backup_dir.exists() and not out_path.exists():
                    shutil.move(str(backup_dir), str(out_path))
                raise
            finally:
                if backup_dir.exists() and out_path.exists():
                    shutil.rmtree(backup_dir, ignore_errors=True)
        finally:
            if staging_dir.exists():
                shutil.rmtree(staging_dir, ignore_errors=True)

        return out_path

    @staticmethod
    def load_parquet_dataset(dataset_dir: Path | str) -> pl.DataFrame:
        """Reads partitioned Parquet dataset back into a single Polars DataFrame."""
        in_path = Path(dataset_dir)
        if not in_path.exists():
            raise FileNotFoundError(f"Dataset path does not exist: {in_path}")

        pattern = str(in_path / "**/*.parquet").replace("\\", "/")
        return pl.read_parquet(pattern, hive_partitioning=True)

    @staticmethod
    def split_train_test_by_session(
        dataset_df: pl.DataFrame,
        test_session_ratio: float = 0.25,
        seed: int = 42,
    ) -> tuple[pl.DataFrame, pl.DataFrame]:
        """Performs group-aware train/test split by session_id to prevent window leakage."""
        sessions = sorted(dataset_df["session_id"].unique().to_list())
        rng = np.random.default_rng(seed)
        shuffled = rng.permutation(sessions).tolist()

        n_test = max(1, int(len(shuffled) * test_session_ratio))
        test_sessions = set(shuffled[:n_test])
        train_sessions = set(shuffled[n_test:])

        if not train_sessions:
            train_df = dataset_df
            test_df = dataset_df.head(0)
            return train_df, test_df

        train_df = dataset_df.filter(pl.col("session_id").is_in(list(train_sessions)))
        test_df = dataset_df.filter(pl.col("session_id").is_in(list(test_sessions)))
        return train_df, test_df
