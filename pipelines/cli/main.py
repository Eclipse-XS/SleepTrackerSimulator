"""SleepSense 2.0 Unified CLI for Data Science, Synthetic Data, and ML Pipelines."""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

import numpy as np
import polars as pl

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from ml.data.sqlite_storage import SqliteStorageManager
from ml.eda.eda_report import run_eda
from ml.experiments.runner import run_experiments
from ml.monitoring.drift_monitor import DriftMonitor
from ml.simulation.generator import PROFILES, SyntheticDatasetGenerator
from pipelines.analytics.duckdb_analytics import SleepDataAnalytics
from pipelines.datasets.dataset_builder import DatasetBuilder
from pipelines.features.feature_extractor import FeatureExtractor
from pipelines.ingestion.android_export_reader import AndroidExportReader
from pipelines.transformation.cleaning import DataCleaner
from pipelines.transformation.synchronizer import SensorSynchronizer
from pipelines.validation.schemas import HeartRateSchema, RawAccelerometerSchema


def cmd_generate(args: argparse.Namespace) -> None:
    """Generates synthetic population, sessions, and analytical SQLite database."""
    profile_name = args.profile.upper()
    if profile_name not in PROFILES:
        raise ValueError(f"Unknown profile '{profile_name}'. Available: {list(PROFILES.keys())}")

    config = PROFILES[profile_name]
    if args.seed is not None:
        from dataclasses import replace
        config = replace(config, master_seed=args.seed)

    db_path = Path(args.db_output)
    sm = SqliteStorageManager(db_path=db_path)
    generator = SyntheticDatasetGenerator(storage_manager=sm)

    parquet_dir = Path(args.parquet_output) if args.parquet_output else None

    print(f"Executing generation for profile: {config.profile_name} (seed={config.master_seed})...")
    manifest = generator.generate_dataset(config=config, parquet_output_dir=parquet_dir)
    print("\nDataset Generation Manifest:")
    print(json.dumps(manifest, indent=2))


def cmd_validate_sql(args: argparse.Namespace) -> None:
    """Runs automated SQL data quality gates against the SQLite dataset."""
    db_path = Path(args.db)
    if not db_path.exists():
        raise FileNotFoundError(f"Database not found: {db_path}")

    sql_path = ROOT / "sql" / "quality_checks.sql"
    sql_text = sql_path.read_text(encoding="utf-8")
    clean_sql = re.sub(r"--.*?\n", "\n", sql_text)
    queries = [q.strip() for q in clean_sql.split(";") if q.strip()]

    conn = sqlite3.connect(str(db_path))
    print(f"Executing {len(queries)} Relational Data Quality Gates against: {db_path}...")
    total_violations = 0
    for q in queries:
        row = conn.execute(q).fetchone()
        gate_name, violations = row[0], row[1]
        status = "PASSED" if violations == 0 else "FAILED"
        print(f"  [{status}] {gate_name}: {violations} violation(s)")
        total_violations += violations

    conn.close()
    if total_violations > 0:
        print(f"\nVALIDATION FAILED: {total_violations} total gate violations detected!", file=sys.stderr)
        sys.exit(1)
    else:
        print("\nALL DATA QUALITY GATES PASSED (Zero Violations).")


def cmd_eda(args: argparse.Namespace) -> None:
    """Generates publication-ready figures and exploratory data analysis."""
    print("Generating Exploratory Data Analysis & Visualizations...")
    stats = run_eda(db_path=args.db, output_dir=args.output_dir)
    print(f"EDA Complete! Output figures saved to: {args.output_dir}")


def cmd_train(args: argparse.Namespace) -> None:
    """Executes leakage-safe ML benchmarks across baselines, classical, and deep learning models."""
    print(f"Running ML Benchmarks and Experiment Suite on: {args.db}...")
    results = run_experiments(db_path=args.db)
    print("Benchmark experiments complete!")


def cmd_monitor(args: argparse.Namespace) -> None:
    """Runs data quality telemetry and PSI feature drift monitoring."""
    print("Running Drift Monitor and Data Quality Telemetry...")
    monitor = DriftMonitor(standard_db=args.standard_db, shifted_db=args.shifted_db)
    report = monitor.compute_monitoring_report()
    print(json.dumps(report, indent=2))


def cmd_run_all(args: argparse.Namespace) -> None:
    """Runs the full end-to-end reproducible workflow."""
    print("=" * 70)
    print("SLEEPSENSE 2.0: FULL END-TO-END DATA SCIENCE & ML WORKFLOW")
    print("=" * 70)

    # 1. Generate Small Dataset
    print("\n--- PHASE 1: GENERATE SYNTHETIC DATASET ---")
    gen_args = argparse.Namespace(
        profile="SMALL",
        seed=42,
        db_output=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"),
        parquet_output=str(ROOT / "data" / "synthetic" / "parquet"),
    )
    cmd_generate(gen_args)

    # 2. Validate Data Quality Gates
    print("\n--- PHASE 2: VALIDATE DATA QUALITY GATES ---")
    val_args = argparse.Namespace(db=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"))
    cmd_validate_sql(val_args)

    # 3. Exploratory Data Analysis
    print("\n--- PHASE 3: EXPLORATORY DATA ANALYSIS ---")
    eda_args = argparse.Namespace(
        db=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"),
        output_dir=str(ROOT / "docs" / "datascience" / "figures"),
    )
    cmd_eda(eda_args)

    # 4. Train & Benchmark ML Models
    print("\n--- PHASE 4: ML BENCHMARKS & MULTIMODAL ABLATION ---")
    train_args = argparse.Namespace(db=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"))
    cmd_train(train_args)

    # 5. Monitor & Drift Analysis
    print("\n--- PHASE 5: DRIFT MONITORING & PSI ANALYSIS ---")
    mon_args = argparse.Namespace(
        standard_db=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"),
        shifted_db=str(ROOT / "data" / "synthetic" / "sleepsense_shifted.sqlite"),
    )
    cmd_monitor(mon_args)

    print("\n" + "=" * 70)
    print("END-TO-END WORKFLOW SUCCESSFULLY COMPLETED!")
    print("=" * 70)


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


def cmd_build_dataset(args: argparse.Namespace) -> None:
    print(f"Building Parquet dataset from: {args.input} -> {args.output}")
    reader = AndroidExportReader()
    input_path = Path(args.input)
    if input_path.suffix.lower() == ".json":
        accel_df, hr_df = reader.read_json_export(input_path)
    else:
        accel_df = reader.read_csv_export(input_path)
        hr_df = pl.DataFrame()

    cleaned_accel = DataCleaner.clean_accelerometer(accel_df)
    cleaned_hr = DataCleaner.clean_heart_rate(hr_df) if not hr_df.is_empty() else hr_df
    windows = SensorSynchronizer.create_synchronized_windows(cleaned_accel, cleaned_hr)
    features_df = FeatureExtractor.extract_all_windows(windows)
    out_dir = DatasetBuilder.build_parquet_dataset(features_df, args.output)
    print(f"SUCCESS: Parquet dataset built and partitioned at: {out_dir}")


def cmd_analyze(args: argparse.Namespace) -> None:
    print(f"Running DuckDB analytics on dataset: {args.dataset}")
    analytics = SleepDataAnalytics(args.dataset)
    print("\n--- Session Summaries ---")
    print(analytics.get_session_summaries())
    print("\n--- Class Distributions ---")
    print(analytics.get_class_distributions())


def main() -> None:
    parser = argparse.ArgumentParser(description="SleepSense 2.0 Unified Data Science & ML Pipeline CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # 1. Run All
    p_run_all = subparsers.add_parser("run-all", help="Execute complete end-to-end data science and ML workflow")

    # 2. Generate
    p_gen = subparsers.add_parser("generate", help="Generate synthetic population, sessions, and SQLite dataset")
    p_gen.add_argument("--profile", default="SMALL", choices=["SMALL", "MEDIUM", "LONG_SESSION", "SHIFTED"], help="Dataset profile")
    p_gen.add_argument("--seed", type=int, default=42, help="Master random seed")
    p_gen.add_argument("--db-output", default=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"), help="Target SQLite database path")
    p_gen.add_argument("--parquet-output", default=str(ROOT / "data" / "synthetic" / "parquet"), help="Optional Parquet output directory")

    # 3. Validate
    p_val = subparsers.add_parser("validate", help="Execute relational SQL data quality gates")
    p_val.add_argument("--db", default=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"), help="Target SQLite database path")

    # 4. EDA
    p_eda = subparsers.add_parser("eda", help="Generate publication-ready EDA charts and figures")
    p_eda.add_argument("--db", default=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"), help="Target SQLite database path")
    p_eda.add_argument("--output-dir", default=str(ROOT / "docs" / "datascience" / "figures"), help="Figure export directory")

    # 5. Train & Benchmarks
    p_train = subparsers.add_parser("train", help="Train baseline and neural models with subject-independent evaluation")
    p_train.add_argument("--db", default=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"), help="Target SQLite database path")

    # 6. Monitor
    p_mon = subparsers.add_parser("monitor", help="Run data quality telemetry and PSI feature drift monitoring")
    p_mon.add_argument("--standard-db", default=str(ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"), help="Baseline SQLite database")
    p_mon.add_argument("--shifted-db", default=str(ROOT / "data" / "synthetic" / "sleepsense_shifted.sqlite"), help="Shifted SQLite database")

    # Backward compatibility commands
    p_ingest = subparsers.add_parser("ingest", help="Ingest Android JSON/CSV export")
    p_ingest.add_argument("--input", required=True)

    p_build = subparsers.add_parser("build-dataset", help="Process raw export and build partitioned Parquet dataset")
    p_build.add_argument("--input", required=True)
    p_build.add_argument("--output", required=True)

    p_analyze = subparsers.add_parser("analyze", help="Run DuckDB SQL analytics on Parquet dataset")
    p_analyze.add_argument("--dataset", required=True)

    args = parser.parse_args()

    dispatch = {
        "run-all": cmd_run_all,
        "generate": cmd_generate,
        "validate": cmd_validate_sql,
        "eda": cmd_eda,
        "train": cmd_train,
        "monitor": cmd_monitor,
        "ingest": cmd_ingest,
        "build-dataset": cmd_build_dataset,
        "analyze": cmd_analyze,
    }

    dispatch[args.command](args)


if __name__ == "__main__":
    main()
