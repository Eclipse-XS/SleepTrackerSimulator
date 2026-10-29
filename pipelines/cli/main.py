"""SleepSense CLI for data engineering pipelines and analytics."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import polars as pl

from pipelines.analytics.duckdb_analytics import SleepDataAnalytics
from pipelines.datasets.dataset_builder import DatasetBuilder
from pipelines.features.feature_extractor import FeatureExtractor
from pipelines.ingestion.android_export_reader import AndroidExportReader
from pipelines.transformation.cleaning import DataCleaner
from pipelines.transformation.synchronizer import SensorSynchronizer
from pipelines.validation.schemas import HeartRateSchema, RawAccelerometerSchema


def generate_synthetic_session(
    session_id: str,
    n_windows: int = 60,
    window_size: int = 20,
    dt_sec: float = 0.05,
    seed: int = 42,
) -> tuple[pl.DataFrame, pl.DataFrame]:
    """Generates synthetic multi-modal accelerometer and heart rate data for testing."""
    rng = np.random.default_rng(seed)
    accel_rows = []
    hr_rows = []

    start_utc = 1730000000000  # Epoch millis reference
    start_elapsed = 1000000000

    current_utc = start_utc
    current_elapsed = start_elapsed

    gravity = np.array([0.0, 0.0, 9.81], dtype=np.float32)

    for w_idx in range(n_windows):
        # Choose state: 0=REST (65%), 1=RESTLESS (25%), 2=ACTIVE (10%)
        p = rng.random()
        if p < 0.65:
            state = "REST"
            base_hr = rng.normal(58.0, 3.0)
        elif p < 0.90:
            state = "RESTLESS"
            base_hr = rng.normal(68.0, 4.0)
        else:
            state = "ACTIVE"
            base_hr = rng.normal(85.0, 6.0)

        t = np.linspace(0.0, 1.0, window_size, dtype=np.float32)

        if state == "REST":
            signal = rng.normal(0.0, 0.02, (window_size, 3))
        elif state == "RESTLESS":
            freq = rng.uniform(1.5, 3.0)
            wave = np.sin(2.0 * np.pi * freq * t)[:, None]
            dir_vec = rng.normal(size=(1, 3))
            dir_vec /= np.linalg.norm(dir_vec)
            signal = wave * dir_vec * rng.uniform(0.3, 0.7) + rng.normal(0.0, 0.05, (window_size, 3))
        else:
            freq = rng.uniform(3.0, 5.0)
            wave = np.sin(2.0 * np.pi * freq * t)[:, None]
            dir_vec = rng.normal(size=(1, 3))
            dir_vec /= np.linalg.norm(dir_vec)
            signal = wave * dir_vec * rng.uniform(1.5, 3.0) + rng.normal(0.0, 0.25, (window_size, 3))

        samples = (signal + gravity).astype(np.float32)

        for s_idx in range(window_size):
            accel_rows.append({
                "session_id": session_id,
                "source_id": "phone_accel_0",
                "elapsed_nanos": int(current_elapsed),
                "utc_millis": int(current_utc),
                "x": float(samples[s_idx, 0]),
                "y": float(samples[s_idx, 1]),
                "z": float(samples[s_idx, 2]),
                "accuracy": 3,
                "source_kind": "PHONE_REAL",
            })
            current_utc += int(dt_sec * 1000)
            current_elapsed += int(dt_sec * 1e9)

        # Heart rate sample every window (approx 1 Hz or 1 per window)
        hr_rows.append({
            "session_id": session_id,
            "device_id": "ble_band_0",
            "bpm": int(max(40, min(180, round(base_hr)))),
            "utc_millis": int(current_utc),
            "source_kind": "BLE_SIMULATED",
        })

    return pl.DataFrame(accel_rows), pl.DataFrame(hr_rows)


def cmd_ingest(args: argparse.Namespace) -> None:
    print(f"Reading export from: {args.input}")
    reader = AndroidExportReader()
    input_path = Path(args.input)
    if input_path.suffix.lower() == ".json":
        accel_df, hr_df = reader.read_json_export(input_path)
    else:
        accel_df = reader.read_csv_export(input_path)
        hr_df = pl.DataFrame()

    print(f"Loaded {len(accel_df)} accelerometer samples across {accel_df['session_id'].n_unique()} session(s).")
    if not hr_df.is_empty():
        print(f"Loaded {len(hr_df)} heart rate samples.")


def cmd_validate(args: argparse.Namespace) -> None:
    print(f"Validating export: {args.input}")
    reader = AndroidExportReader()
    input_path = Path(args.input)
    if input_path.suffix.lower() == ".json":
        accel_df, hr_df = reader.read_json_export(input_path)
    else:
        accel_df = reader.read_csv_export(input_path)
        hr_df = pl.DataFrame()

    import pandera.errors

    try:
        RawAccelerometerSchema.validate(accel_df)
        print("PASS: Accelerometer data conforms to RawAccelerometerSchema.")
    except pandera.errors.SchemaError as e:
        print(f"FAIL: Accelerometer data validation error: {e}", file=sys.stderr)
        sys.exit(1)

    if not hr_df.is_empty():
        try:
            HeartRateSchema.validate(hr_df)
            print("PASS: Heart rate data conforms to HeartRateSchema.")
        except pandera.errors.SchemaError as e:
            print(f"FAIL: Heart rate data validation error: {e}", file=sys.stderr)
            sys.exit(1)


def cmd_build_dataset(args: argparse.Namespace) -> None:
    print(f"Building Parquet dataset from: {args.input} -> {args.output}")
    reader = AndroidExportReader()
    input_path = Path(args.input)
    if input_path.suffix.lower() == ".json":
        accel_df, hr_df = reader.read_json_export(input_path)
    else:
        accel_df = reader.read_csv_export(input_path)
        hr_df = pl.DataFrame()

    # Step 1: Clean
    cleaned_accel = DataCleaner.clean_accelerometer(accel_df)
    cleaned_hr = DataCleaner.clean_heart_rate(hr_df) if not hr_df.is_empty() else hr_df

    # Step 2: Synchronize
    windows = SensorSynchronizer.create_synchronized_windows(cleaned_accel, cleaned_hr)
    print(f"Generated {len(windows)} synchronized windows.")

    # Step 3: Extract Features
    features_df = FeatureExtractor.extract_all_windows(windows)

    # Step 4: Build Parquet
    out_dir = DatasetBuilder.build_parquet_dataset(features_df, args.output)
    print(f"SUCCESS: Parquet dataset built and partitioned at: {out_dir}")


def cmd_analyze(args: argparse.Namespace) -> None:
    print(f"Running DuckDB analytics on dataset: {args.dataset}")
    analytics = SleepDataAnalytics(args.dataset)

    print("\n--- Session Summaries ---")
    summaries = analytics.get_session_summaries()
    print(summaries)

    print("\n--- Class Distributions ---")
    dist = analytics.get_class_distributions()
    print(dist)

    print("\n--- Rest Quality Scores ---")
    scores = analytics.get_rest_quality_scores()
    print(scores)

    corr = analytics.get_multimodal_correlation()
    if not corr.is_empty():
        print("\n--- Multimodal Correlation (Motion vs HR) ---")
        print(corr)


def cmd_generate_synthetic(args: argparse.Namespace) -> None:
    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)
    all_accel = []
    all_hr = []

    print(f"Generating synthetic data for {args.sessions} sessions ({args.windows} windows each)...")
    for s_idx in range(args.sessions):
        sid = f"session-synth-{s_idx:03d}"
        a_df, h_df = generate_synthetic_session(
            session_id=sid,
            n_windows=args.windows,
            seed=args.seed + s_idx,
        )
        all_accel.append(a_df)
        all_hr.append(h_df)

    comb_accel = pl.concat(all_accel)
    comb_hr = pl.concat(all_hr)

    # Process and build dataset
    cleaned_accel = DataCleaner.clean_accelerometer(comb_accel)
    cleaned_hr = DataCleaner.clean_heart_rate(comb_hr)
    windows = SensorSynchronizer.create_synchronized_windows(cleaned_accel, cleaned_hr)
    features_df = FeatureExtractor.extract_all_windows(windows)

    DatasetBuilder.build_parquet_dataset(features_df, out_dir)
    print(f"SUCCESS: Generated {len(features_df)} windows saved to Parquet at: {out_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description="SleepSense Data Engineering Pipeline CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # Ingest
    p_ingest = subparsers.add_parser("ingest", help="Ingest Android JSON/CSV export")
    p_ingest.add_argument("--input", required=True, help="Input JSON or CSV file")

    # Validate
    p_validate = subparsers.add_parser("validate", help="Validate raw data with Pandera schemas")
    p_validate.add_argument("--input", required=True, help="Input JSON or CSV file")

    # Build dataset
    p_build = subparsers.add_parser("build-dataset", help="Process raw export and build partitioned Parquet dataset")
    p_build.add_argument("--input", required=True, help="Input JSON or CSV file")
    p_build.add_argument("--output", required=True, help="Output directory for Parquet dataset")

    # Analyze
    p_analyze = subparsers.add_parser("analyze", help="Run DuckDB SQL analytics on Parquet dataset")
    p_analyze.add_argument("--dataset", required=True, help="Path to Parquet dataset directory")

    # Generate synthetic
    p_gen = subparsers.add_parser("generate-synthetic", help="Generate synthetic test data and Parquet dataset")
    p_gen.add_argument("--output", default="data/processed/synthetic_dataset", help="Output directory")
    p_gen.add_argument("--sessions", type=int, default=5, help="Number of sessions")
    p_gen.add_argument("--windows", type=int, default=60, help="Windows per session")
    p_gen.add_argument("--seed", type=int, default=42, help="Random seed")

    args = parser.parse_args()

    if args.command == "ingest":
        cmd_ingest(args)
    elif args.command == "validate":
        cmd_validate(args)
    elif args.command == "build-dataset":
        cmd_build_dataset(args)
    elif args.command == "analyze":
        cmd_analyze(args)
    elif args.command == "generate-synthetic":
        cmd_generate_synthetic(args)


if __name__ == "__main__":
    main()
