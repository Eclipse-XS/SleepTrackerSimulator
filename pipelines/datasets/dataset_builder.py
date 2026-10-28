"""Dataset builder module for exporting and partitioning feature datasets to Parquet."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import polars as pl
from pyarrow import parquet as pq

from pipelines.validation.schemas import WindowFeaturesSchema


class DatasetBuilder:
    """Builds, validates, partitions, and loads Parquet datasets for SleepSense."""

    @staticmethod
    def build_parquet_dataset(
        features_df: pl.DataFrame,
        output_dir: Path | str,
        partition_cols: list[str] | None = None,
    ) -> Path:
        """Validates features against WindowFeaturesSchema and writes partitioned Parquet files."""
        out_path = Path(output_dir)
        out_path.mkdir(parents=True, exist_ok=True)

        if partition_cols is None:
            partition_cols = ["session_id", "label"]

        # Validate with Pandera
        validated_df = WindowFeaturesSchema.validate(features_df)

        # Convert to PyArrow Table
        arrow_table = validated_df.to_arrow()

        # Write partitioned dataset
        pq.write_to_dataset(
            arrow_table,
            root_path=str(out_path),
            partition_cols=partition_cols,
            existing_data_behavior="overwrite_or_ignore",
            use_dictionary=True,
            compression="SNAPPY",
        )

        # Compute dataset summary metadata
        class_counts = (
            validated_df.group_by("label")
            .agg(pl.len().alias("count"))
            .to_dicts()
        )
        classes_map = {row["label"]: row["count"] for row in class_counts}

        prov_counts = (
            validated_df.group_by("provenance")
            .agg(pl.len().alias("count"))
            .to_dicts()
        )
        prov_map = {row["provenance"]: row["count"] for row in prov_counts}

        unique_sessions = validated_df["session_id"].n_unique()
        total_rows = len(validated_df)

        metadata = {
            "total_windows": total_rows,
            "unique_sessions": unique_sessions,
            "classes": classes_map,
            "provenances": prov_map,
            "partition_columns": partition_cols,
            "columns": validated_df.columns,
            "min_start_utc": int(validated_df["start_utc_millis"].min()) if total_rows > 0 else 0,
            "max_end_utc": int(validated_df["end_utc_millis"].max()) if total_rows > 0 else 0,
        }

        meta_file = out_path / "dataset_metadata.json"
        meta_file.write_text(json.dumps(metadata, indent=2), encoding="utf-8")

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
            # Fallback if only 1 session total
            train_df = dataset_df
            test_df = dataset_df.head(0)
            return train_df, test_df

        train_df = dataset_df.filter(pl.col("session_id").is_in(list(train_sessions)))
        test_df = dataset_df.filter(pl.col("session_id").is_in(list(test_sessions)))
        return train_df, test_df
