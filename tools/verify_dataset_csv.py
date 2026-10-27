#!/usr/bin/env python3
"""
SleepSense 2.0 Dataset CSV Verification & ML Training Pipeline
Verifies that exported CSV dataset files comply with the schema, are loadable
by pandas without errors, and can be ingested directly into a reproducible ML pipeline.
"""

import sys
import os
import io
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report, accuracy_score
from sklearn.model_selection import train_test_split


def generate_benchmark_csv_content():
    """Generates a realistic SleepSense dataset CSV conforming to specification."""
    lines = [
        "# SleepSense Ground-Truth Accelerometry Dataset v2.0",
        "# SessionId: test-session-benchmark-001",
        "# ExperimentType: CONTROLLED_REST",
        "# Placement: MATTRESS",
        "# GroundTruthActivity: RESTING",
        "# DeviceModel: Google Pixel 10 Pro",
        "# StartTimeUtc: 2026-10-27T06:00:00.000Z",
        "# EndTimeUtc: 2026-10-27T06:01:00.000Z",
        "# DurationSeconds: 60.000",
        "# TotalSamples: 1200",
        "# ValidSamples: 1200",
        "# RejectedSamples: 0",
        "# ActualSamplingRateHz: 20.00",
        "timestamp_utc_ms,elapsed_realtime_ns,accel_x,accel_y,accel_z,dynamic_magnitude,is_valid,ground_truth_label,predicted_motion_class",
    ]

    base_utc = 1793080800000
    base_ns = 50000000000000

    np.random.seed(42)

    # 400 samples of RESTING (low dynamic magnitude)
    for i in range(400):
        t_utc = base_utc + i * 50
        t_ns = base_ns + i * 50000000
        x = np.random.normal(0.05, 0.02)
        y = np.random.normal(0.02, 0.02)
        z = np.random.normal(9.81, 0.03)
        mag = abs(np.sqrt(x*x + y*y + z*z) - 9.80665)
        lines.append(f"{t_utc},{t_ns},{x:.5f},{y:.5f},{z:.5f},{mag:.5f},True,RESTING,REST")

    # 400 samples of READING (moderate hand jitter)
    for i in range(400, 800):
        t_utc = base_utc + i * 50
        t_ns = base_ns + i * 50000000
        x = np.random.normal(0.8, 0.25)
        y = np.random.normal(1.2, 0.3)
        z = np.random.normal(9.6, 0.25)
        mag = abs(np.sqrt(x*x + y*y + z*z) - 9.80665)
        lines.append(f"{t_utc},{t_ns},{x:.5f},{y:.5f},{z:.5f},{mag:.5f},True,READING,RESTLESS")

    # 400 samples of OUT_OF_BED / WALKING (high dynamic magnitude)
    for i in range(800, 1200):
        t_utc = base_utc + i * 50
        t_ns = base_ns + i * 50000000
        x = np.random.normal(2.5, 1.2)
        y = np.random.normal(3.8, 1.5)
        z = np.random.normal(10.5, 1.8)
        mag = abs(np.sqrt(x*x + y*y + z*z) - 9.80665)
        lines.append(f"{t_utc},{t_ns},{x:.5f},{y:.5f},{z:.5f},{mag:.5f},True,OUT_OF_BED,ACTIVE")

    return "\n".join(lines)


def verify_dataset(csv_path_or_content):
    print("=== SLEEPSENSE DATASET CSV VERIFICATION ===")

    metadata = {}
    if os.path.exists(csv_path_or_content):
        with open(csv_path_or_content, "r", encoding="utf-8") as f:
            raw_text = f.read()
    else:
        raw_text = csv_path_or_content

    for line in raw_text.splitlines():
        if line.startswith("#"):
            parts = line[1:].strip().split(":", 1)
            if len(parts) == 2:
                metadata[parts[0].strip()] = parts[1].strip()

    print(f"Metadata extracted: {len(metadata)} fields")
    for k, v in metadata.items():
        print(f"  {k}: {v}")

    # Load with pandas, ignoring '#' comments
    df = pd.read_csv(io.StringIO(raw_text), comment="#")
    print(f"\nDataFrame shape: {df.shape}")
    print("Columns:", list(df.columns))

    expected_cols = [
        "timestamp_utc_ms", "elapsed_realtime_ns",
        "accel_x", "accel_y", "accel_z", "dynamic_magnitude",
        "is_valid", "ground_truth_label", "predicted_motion_class"
    ]
    assert list(df.columns) == expected_cols, f"Column mismatch: {df.columns}"

    # Assert no nulls in valid samples
    valid_df = df[df["is_valid"] == True]
    assert valid_df.isnull().sum().sum() == 0, "Null values found in valid dataset rows"

    # Verify sampling rate
    dt_ms = valid_df["timestamp_utc_ms"].diff().dropna()
    median_dt = dt_ms.median()
    inferred_hz = 1000.0 / median_dt if median_dt > 0 else 0
    print(f"Inferred Sampling Rate: {inferred_hz:.2f} Hz (Median interval: {median_dt:.1f} ms)")
    assert 15.0 <= inferred_hz <= 25.0, f"Sampling rate {inferred_hz} outside expected 20 Hz range"

    # ML Feature Extraction & Windowing (Window size = 20 samples = 1 sec)
    window_size = 20
    features = []
    labels = []

    for i in range(0, len(valid_df) - window_size, window_size):
        chunk = valid_df.iloc[i:i + window_size]
        x_rms = np.sqrt(np.mean(chunk["accel_x"]**2))
        y_rms = np.sqrt(np.mean(chunk["accel_y"]**2))
        z_rms = np.sqrt(np.mean(chunk["accel_z"]**2))
        mag_mean = chunk["dynamic_magnitude"].mean()
        mag_std = chunk["dynamic_magnitude"].std()
        mag_max = chunk["dynamic_magnitude"].max()

        # Majority ground truth label in window
        gt = chunk["ground_truth_label"].mode()[0]

        features.append([x_rms, y_rms, z_rms, mag_mean, mag_std, mag_max])
        labels.append(gt)

    X = np.array(features)
    y = np.array(labels)

    print(f"\nExtracted {len(X)} windows for ML training. Unique classes: {np.unique(y)}")

    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.3, random_state=42, stratify=y)
    clf = RandomForestClassifier(n_estimators=30, random_state=42)
    clf.fit(X_train, y_train)

    preds = clf.predict(X_test)
    acc = accuracy_score(y_test, preds)

    print(f"\nRandom Forest Test Accuracy on Ground Truth: {acc * 100:.2f}%")
    print("\nClassification Report:")
    print(classification_report(y_test, preds))

    assert acc >= 0.85, f"Accuracy {acc} below benchmark threshold 0.85"
    print("VERIFICATION RESULT: ALL CHECKS PASSED (Schema, Pandas, ML Ingestion).")


if __name__ == "__main__":
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        verify_dataset(sys.argv[1])
    else:
        print("Running benchmark generation and verification...")
        sample_csv = generate_benchmark_csv_content()
        verify_dataset(sample_csv)
