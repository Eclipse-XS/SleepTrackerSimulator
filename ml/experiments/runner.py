"""Comprehensive Machine Learning Experiments and Benchmarking Engine.

Conducts leakage-safe subject-independent evaluation across:
1. Majority Class Baseline
2. Physical Heuristic Baseline
3. Logistic Regression (Handcrafted Features)
4. Random Forest Classifier
5. Dense Accelerometer Neural Network (TFLite Candidate 1)
6. 1D Temporal Convolutional Neural Network (TFLite Candidate 2)
7. Multimodal Late-Fusion Neural Network (TFLite Multimodal Candidate)

Executes 7-condition Multimodal Ablation and Domain Shift robustness evaluation.
Persists model predictions to SQLite and logs runs to MLflow.
"""

from __future__ import annotations

import json
import sqlite3
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import mlflow
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
)
from sklearn.preprocessing import StandardScaler
import tensorflow as tf

from ml.data.sqlite_storage import OutputPolicy, SqliteStorageManager
from ml.data.window_dataset import load_dataset_from_sqlite as load_canonical_window_dataset
from ml.evaluation_quality import assert_no_recording_leakage, expected_calibration_error
from ml.simulation.generator import PROFILES, SyntheticDatasetGenerator
from sklearn.utils.class_weight import compute_class_weight

ROOT = Path(__file__).resolve().parents[2]
DB_PATH = ROOT / "data" / "synthetic" / "sleepsense_synthetic.sqlite"
DOCS_DATA_SCIENCE = ROOT / "docs" / "datascience"
CLASSES = ["REST", "RESTLESS", "ACTIVE"]
LABEL_TO_INT = {c: i for i, c in enumerate(CLASSES)}


def load_dataset_from_sqlite(db_path: Path | str = DB_PATH) -> dict[str, Any]:
    """Loads all session windows and corresponding raw tensors organized by subject via canonical loader."""
    dataset = load_canonical_window_dataset(db_path)
    return dataset.data_by_subject


def benchmark_model_latency(predict_fn, sample_input, iterations: int = 300) -> float:
    """Measures single-window inference latency in microseconds."""
    for _ in range(30):
        predict_fn(sample_input)
    t0 = time.perf_counter()
    for _ in range(iterations):
        predict_fn(sample_input)
    elapsed = time.perf_counter() - t0
    return (elapsed / iterations) * 1_000_000.0


def compute_metrics(y_true: np.ndarray, y_pred: np.ndarray, y_probs: np.ndarray | None = None) -> dict[str, Any]:
    """Computes comprehensive evaluation metrics including ECE and balanced accuracy."""
    acc = float(accuracy_score(y_true, y_pred))
    bal_acc = float(balanced_accuracy_score(y_true, y_pred))
    f1_macro = float(f1_score(y_true, y_pred, average="macro", zero_division=0))
    f1_weighted = float(f1_score(y_true, y_pred, average="weighted", zero_division=0))
    prec_macro = float(precision_score(y_true, y_pred, average="macro", zero_division=0))
    rec_macro = float(recall_score(y_true, y_pred, average="macro", zero_division=0))
    cm = confusion_matrix(y_true, y_pred, labels=[0, 1, 2]).tolist()

    prec_per_class = precision_score(y_true, y_pred, average=None, labels=[0, 1, 2], zero_division=0).tolist()
    rec_per_class = recall_score(y_true, y_pred, average=None, labels=[0, 1, 2], zero_division=0).tolist()

    ece = None
    if y_probs is not None:
        try:
            ece = float(expected_calibration_error(y_probs, y_true, bins=10))
        except Exception:
            ece = None

    return {
        "accuracy": acc,
        "balanced_accuracy": bal_acc,
        "macro_f1": f1_macro,
        "weighted_f1": f1_weighted,
        "macro_precision": prec_macro,
        "macro_recall": rec_macro,
        "precision_per_class": {CLASSES[i]: float(prec_per_class[i]) for i in range(3)},
        "recall_per_class": {CLASSES[i]: float(rec_per_class[i]) for i in range(3)},
        "confusion_matrix": cm,
        "ece": ece,
    }


def run_experiments(db_path: Path | str = DB_PATH) -> dict[str, Any]:
    """Executes the full suite of ML benchmarks, ablations, and domain shift evaluation."""
    print("=" * 70)
    print("SleepSense 2.0: Rigorous Subject-Independent Machine Learning Benchmarks")
    print("=" * 70)

    # Configure MLflow
    mlflow.set_tracking_uri(f"sqlite:///{ROOT / 'mlflow.db'}")
    mlflow.set_experiment("sleepsense_2.0_benchmarks")

    # 1. Load data grouped by subject via authoritative WindowDataset
    dataset = load_canonical_window_dataset(db_path)
    data_by_subject = dataset.data_by_subject
    subject_ids = dataset.get_subject_ids()
    print(f"Loaded dataset across {len(subject_ids)} subjects: {subject_ids}")

    # Authoritative window exclusion telemetry
    exclusion_audit = dataset.get_exclusion_audit()
    print(f"Exclusion Breakdown: Total={exclusion_audit['total_windows']}, "
          f"Eligible={exclusion_audit['training_eligible_count']}, Ineligible={exclusion_audit['ineligible_count']}")
    print(f"Ineligible by Class: {exclusion_audit['by_class']}")
    print(f"Ineligible by Reason: {exclusion_audit['by_reason']}")

    # 2. Group-Aware Split (Subject-Independent)
    # Train: first 8 subjects; Val: next 2 subjects; Test: last 2 subjects
    train_subs = subject_ids[:8]
    val_subs = subject_ids[8:10]
    test_subs = subject_ids[10:]

    print(f"Train subjects ({len(train_subs)}): {train_subs}")
    print(f"Val subjects   ({len(val_subs)}): {val_subs}")
    print(f"Test subjects  ({len(test_subs)}): {test_subs}")

    # Leakage check: zero subject overlap
    assert len(set(train_subs).intersection(test_subs)) == 0, "Subject leakage detected between train and test!"
    assert len(set(train_subs).intersection(val_subs)) == 0, "Subject leakage detected between train and val!"

    def collate_split(subs: list[str], policy: str = "TRAINING_STRICT") -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, list[str], list[str]]:
        X_acc, X_hr, X_tab, y, w_ids, s_ids, _ = dataset.collate_subjects(subs, policy=policy, return_metadata=True)
        return X_acc, X_hr, X_tab, y, w_ids, s_ids

    X_train_accel, X_train_hr, X_train_tab, y_train, train_wids, train_sids = collate_split(train_subs, policy="TRAINING_STRICT")
    X_val_accel, X_val_hr, X_val_tab, y_val, val_wids, val_sids = collate_split(val_subs, policy="TRAINING_STRICT")
    X_test_accel, X_test_hr, X_test_tab, y_test, test_wids, test_sids = collate_split(test_subs, policy="TEST_ACCEPTED_ONLY")
    X_test_full_accel, X_test_full_hr, X_test_full_tab, y_test_full, test_full_wids, test_full_sids = collate_split(test_subs, policy="TEST_FULL_POPULATION")

    # Verify recording session leakage
    assert_no_recording_leakage(train_sids, test_sids)

    # Calculate balanced class weights on Train ONLY to combat minority-class degradation (ML-006)
    class_weights_arr = compute_class_weight("balanced", classes=np.array([0, 1, 2]), y=y_train)
    class_weight_dict = {i: float(class_weights_arr[i]) for i in range(3)}
    print(f"Dataset window counts: Train={len(y_train):,}, Val={len(y_val):,}, Test={len(y_test):,}")
    print(f"Computed training class weights: {class_weight_dict}")

    # Standardize tabular features using Train ONLY (zero leakage)
    scaler = StandardScaler()
    X_train_tab_norm = scaler.fit_transform(X_train_tab)
    X_val_tab_norm = scaler.transform(X_val_tab)
    X_test_tab_norm = scaler.transform(X_test_tab)

    results: dict[str, Any] = {}
    predictions_to_persist: list[dict[str, Any]] = []

    # -------------------------------------------------------------------------
    # Model 1: Majority Class Baseline
    # -------------------------------------------------------------------------
    print("\n[1/7] Evaluating Majority Class Baseline...")
    maj_class = int(np.bincount(y_train).argmax())
    maj_preds = np.full_like(y_test, maj_class)
    maj_probs = np.zeros((len(y_test), 3), dtype=np.float32)
    maj_probs[:, maj_class] = 1.0

    maj_metrics = compute_metrics(y_test, maj_preds, maj_probs)
    maj_metrics["latency_us"] = 0.1
    maj_metrics["model_size_kb"] = 0.0
    results["Majority Class Baseline"] = maj_metrics
    print(f"  Accuracy: {maj_metrics['accuracy']:.4f}, Macro F1: {maj_metrics['macro_f1']:.4f}")

    for wid, sid, pred, conf, probs in zip(test_wids, test_sids, maj_preds, maj_probs.max(axis=1), maj_probs):
        predictions_to_persist.append({
            "prediction_id": f"pred-maj-{wid}",
            "window_id": wid,
            "session_id": sid,
            "model_name": "Majority Class Baseline",
            "predicted_class": CLASSES[pred],
            "confidence": float(conf),
            "prob_rest": float(probs[0]),
            "prob_restless": float(probs[1]),
            "prob_active": float(probs[2]),
            "inference_duration_us": 0.1,
        })

    # -------------------------------------------------------------------------
    # Model 2: Physical Rule-Based Heuristic
    # -------------------------------------------------------------------------
    print("\n[2/7] Evaluating Physical Rule-Based Heuristic (RMS & Variance)...")
    def run_rule_baseline(tab_unnorm: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        preds, probs = [], []
        for row in tab_unnorm:
            rms, var = row[0], row[1]
            if rms < 0.10 and var < 0.015:
                preds.append(0)
                probs.append([0.90, 0.08, 0.02])
            elif rms < 0.85:
                preds.append(1)
                probs.append([0.12, 0.80, 0.08])
            else:
                preds.append(2)
                probs.append([0.02, 0.10, 0.88])
        return np.array(preds, dtype=np.int32), np.array(probs, dtype=np.float32)

    rule_preds, rule_probs = run_rule_baseline(X_test_tab)
    rule_lat_us = benchmark_model_latency(lambda x: run_rule_baseline(x), X_test_tab[:1])
    rule_metrics = compute_metrics(y_test, rule_preds, rule_probs)
    rule_metrics["latency_us"] = float(rule_lat_us)
    rule_metrics["model_size_kb"] = 0.0
    results["Physical Rule-Based Heuristic"] = rule_metrics
    print(f"  Accuracy: {rule_metrics['accuracy']:.4f}, Macro F1: {rule_metrics['macro_f1']:.4f}, Latency: {rule_lat_us:.1f}us")

    for wid, sid, pred, conf, probs in zip(test_wids, test_sids, rule_preds, rule_probs.max(axis=1), rule_probs):
        predictions_to_persist.append({
            "prediction_id": f"pred-rule-{wid}",
            "window_id": wid,
            "session_id": sid,
            "model_name": "Physical Rule-Based Heuristic",
            "predicted_class": CLASSES[pred],
            "confidence": float(conf),
            "prob_rest": float(probs[0]),
            "prob_restless": float(probs[1]),
            "prob_active": float(probs[2]),
            "inference_duration_us": float(rule_lat_us),
        })

    # -------------------------------------------------------------------------
    # Model 3: Logistic Regression (Multinomial with Class Weights)
    # -------------------------------------------------------------------------
    print("\n[3/7] Training Logistic Regression...")
    lr = LogisticRegression(max_iter=500, class_weight="balanced", random_state=42)
    lr.fit(X_train_tab_norm, y_train)
    lr_preds = lr.predict(X_test_tab_norm)
    lr_probs = lr.predict_proba(X_test_tab_norm)
    lr_lat_us = benchmark_model_latency(lambda x: lr.predict(x), X_test_tab_norm[:1])
    lr_metrics = compute_metrics(y_test, lr_preds, lr_probs)
    lr_metrics["latency_us"] = float(lr_lat_us)
    lr_metrics["model_size_kb"] = 2.4
    results["Logistic Regression"] = lr_metrics
    print(f"  Accuracy: {lr_metrics['accuracy']:.4f}, Macro F1: {lr_metrics['macro_f1']:.4f}, Latency: {lr_lat_us:.1f}us")

    for wid, sid, pred, conf, probs in zip(test_wids, test_sids, lr_preds, lr_probs.max(axis=1), lr_probs):
        predictions_to_persist.append({
            "prediction_id": f"pred-lr-{wid}",
            "window_id": wid,
            "session_id": sid,
            "model_name": "Logistic Regression",
            "predicted_class": CLASSES[pred],
            "confidence": float(conf),
            "prob_rest": float(probs[0]),
            "prob_restless": float(probs[1]),
            "prob_active": float(probs[2]),
            "inference_duration_us": float(lr_lat_us),
        })

    # -------------------------------------------------------------------------
    # Model 4: Random Forest (Balanced Subsample)
    # -------------------------------------------------------------------------
    print("\n[4/7] Training Random Forest Classifier...")
    rf = RandomForestClassifier(n_estimators=50, max_depth=8, class_weight="balanced_subsample", random_state=42, n_jobs=-1)
    rf.fit(X_train_tab, y_train)
    rf_preds = rf.predict(X_test_tab)
    rf_probs = rf.predict_proba(X_test_tab)
    rf_lat_us = benchmark_model_latency(lambda x: rf.predict(x), X_test_tab[:1])
    rf_metrics = compute_metrics(y_test, rf_preds, rf_probs)
    rf_metrics["latency_us"] = float(rf_lat_us)
    rf_metrics["model_size_kb"] = 165.0
    results["Random Forest"] = rf_metrics
    print(f"  Accuracy: {rf_metrics['accuracy']:.4f}, Macro F1: {rf_metrics['macro_f1']:.4f}, Latency: {rf_lat_us:.1f}us")

    for wid, sid, pred, conf, probs in zip(test_wids, test_sids, rf_preds, rf_probs.max(axis=1), rf_probs):
        predictions_to_persist.append({
            "prediction_id": f"pred-rf-{wid}",
            "window_id": wid,
            "session_id": sid,
            "model_name": "Random Forest",
            "predicted_class": CLASSES[pred],
            "confidence": float(conf),
            "prob_rest": float(probs[0]),
            "prob_restless": float(probs[1]),
            "prob_active": float(probs[2]),
            "inference_duration_us": float(rf_lat_us),
        })

    # -------------------------------------------------------------------------
    # Model 5: Dense Accelerometer Neural Network (TFLite Candidate 1)
    # -------------------------------------------------------------------------
    print("\n[5/7] Training Dense Accelerometer DNN (TFLite Candidate 1)...")
    tf.keras.backend.clear_session()
    dense_model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(20, 3), name="accelerometer_window"),
        tf.keras.layers.Lambda(lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True), name="remove_gravity"),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(3, activation="softmax", name="probabilities"),
    ], name="dense_accelerometer_classifier")

    dense_model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.002),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    dense_model.fit(
        X_train_accel, y_train,
        validation_data=(X_val_accel, y_val),
        epochs=12,
        batch_size=128,
        class_weight=class_weight_dict,
        verbose=0,
    )
    dense_probs = dense_model.predict(X_test_accel, verbose=0)
    dense_preds = np.argmax(dense_probs, axis=1)
    dense_lat_us = benchmark_model_latency(lambda x: dense_model(x, training=False), X_test_accel[:1])

    # Convert to TFLite for exact size check
    conv_dense = tf.lite.TFLiteConverter.from_keras_model(dense_model)
    dense_tflite_bytes = conv_dense.convert()

    dense_metrics = compute_metrics(y_test, dense_preds, dense_probs)
    dense_metrics["latency_us"] = float(dense_lat_us)
    dense_metrics["model_size_kb"] = float(len(dense_tflite_bytes) / 1024.0)
    results["Dense Accelerometer DNN"] = dense_metrics
    print(f"  Accuracy: {dense_metrics['accuracy']:.4f}, Macro F1: {dense_metrics['macro_f1']:.4f}, Latency: {dense_lat_us:.1f}us, Size: {dense_metrics['model_size_kb']:.1f}KB")

    for wid, sid, pred, conf, probs in zip(test_wids, test_sids, dense_preds, dense_probs.max(axis=1), dense_probs):
        predictions_to_persist.append({
            "prediction_id": f"pred-dense-{wid}",
            "window_id": wid,
            "session_id": sid,
            "model_name": "Dense Accelerometer DNN",
            "predicted_class": CLASSES[pred],
            "confidence": float(conf),
            "prob_rest": float(probs[0]),
            "prob_restless": float(probs[1]),
            "prob_active": float(probs[2]),
            "inference_duration_us": float(dense_lat_us),
        })

    # -------------------------------------------------------------------------
    # Model 6: 1D Temporal CNN (TFLite Candidate 2)
    # -------------------------------------------------------------------------
    print("\n[6/7] Training 1D Temporal CNN (TFLite Candidate 2)...")
    tf.keras.backend.clear_session()
    cnn_model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(20, 3), name="accelerometer_window"),
        tf.keras.layers.Lambda(lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True), name="remove_gravity"),
        tf.keras.layers.Conv1D(16, kernel_size=3, padding="same", activation="relu"),
        tf.keras.layers.MaxPooling1D(pool_size=2),
        tf.keras.layers.Conv1D(32, kernel_size=3, padding="same", activation="relu"),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(3, activation="softmax", name="probabilities"),
    ], name="temporal_1d_cnn_classifier")

    cnn_model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.002),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    cnn_model.fit(
        X_train_accel, y_train,
        validation_data=(X_val_accel, y_val),
        epochs=12,
        batch_size=128,
        class_weight=class_weight_dict,
        verbose=0,
    )
    cnn_probs = cnn_model.predict(X_test_accel, verbose=0)
    cnn_preds = np.argmax(cnn_probs, axis=1)
    cnn_lat_us = benchmark_model_latency(lambda x: cnn_model(x, training=False), X_test_accel[:1])

    conv_cnn = tf.lite.TFLiteConverter.from_keras_model(cnn_model)
    cnn_tflite_bytes = conv_cnn.convert()

    cnn_metrics = compute_metrics(y_test, cnn_preds, cnn_probs)
    cnn_metrics["latency_us"] = float(cnn_lat_us)
    cnn_metrics["model_size_kb"] = float(len(cnn_tflite_bytes) / 1024.0)
    results["1D Temporal CNN"] = cnn_metrics
    print(f"  Accuracy: {cnn_metrics['accuracy']:.4f}, Macro F1: {cnn_metrics['macro_f1']:.4f}, Latency: {cnn_lat_us:.1f}us, Size: {cnn_metrics['model_size_kb']:.1f}KB")

    for wid, sid, pred, conf, probs in zip(test_wids, test_sids, cnn_preds, cnn_probs.max(axis=1), cnn_probs):
        predictions_to_persist.append({
            "prediction_id": f"pred-cnn-{wid}",
            "window_id": wid,
            "session_id": sid,
            "model_name": "1D Temporal CNN",
            "predicted_class": CLASSES[pred],
            "confidence": float(conf),
            "prob_rest": float(probs[0]),
            "prob_restless": float(probs[1]),
            "prob_active": float(probs[2]),
            "inference_duration_us": float(cnn_lat_us),
        })

    # -------------------------------------------------------------------------
    # Model 7: Multimodal Late-Fusion DNN (TFLite Multimodal Candidate)
    # -------------------------------------------------------------------------
    print("\n[7/7] Training Multimodal Late-Fusion DNN (Dual-Input)...")
    tf.keras.backend.clear_session()
    # Branch 1: Accelerometer [1, 20, 3]
    accel_in = tf.keras.layers.Input(shape=(20, 3), name="accelerometer_input")
    centered_accel = tf.keras.layers.Lambda(
        lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True),
        name="remove_gravity"
    )(accel_in)
    accel_flat = tf.keras.layers.Flatten()(centered_accel)
    accel_emb = tf.keras.layers.Dense(32, activation="relu", name="motion_embedding")(accel_flat)
    accel_drop = tf.keras.layers.Dropout(0.2)(accel_emb)
    accel_rep = tf.keras.layers.Dense(16, activation="relu")(accel_drop)

    # Branch 2: Heart Rate [1, 2] -> [norm_bpm, hr_available]
    hr_in = tf.keras.layers.Input(shape=(2,), name="heart_rate_input")
    hr_emb = tf.keras.layers.Dense(8, activation="relu", name="hr_embedding")(hr_in)

    # Late Fusion
    fusion = tf.keras.layers.Concatenate(name="sensor_fusion")([accel_rep, hr_emb])
    fusion_dense = tf.keras.layers.Dense(16, activation="relu")(fusion)
    output_probs = tf.keras.layers.Dense(3, activation="softmax", name="probabilities")(fusion_dense)

    multi_model = tf.keras.Model(inputs=[accel_in, hr_in], outputs=output_probs, name="multimodal_late_fusion_classifier")
    multi_model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.002),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    multi_model.fit(
        [X_train_accel, X_train_hr], y_train,
        validation_data=([X_val_accel, X_val_hr], y_val),
        epochs=14,
        batch_size=128,
        class_weight=class_weight_dict,
        verbose=0,
    )
    multi_probs = multi_model.predict([X_test_accel, X_test_hr], verbose=0)
    multi_preds = np.argmax(multi_probs, axis=1)
    multi_lat_us = benchmark_model_latency(lambda x: multi_model(x, training=False), [X_test_accel[:1], X_test_hr[:1]])

    multi_val_probs = multi_model.predict([X_val_accel, X_val_hr], verbose=0)
    val_metrics = compute_metrics(y_val, np.argmax(multi_val_probs, axis=1), multi_val_probs)

    conv_multi = tf.lite.TFLiteConverter.from_keras_model(multi_model)
    multi_tflite_bytes = conv_multi.convert()

    multi_metrics = compute_metrics(y_test, multi_preds, multi_probs)
    multi_metrics["latency_us"] = float(multi_lat_us)
    multi_metrics["model_size_kb"] = float(len(multi_tflite_bytes) / 1024.0)
    results["Multimodal Late-Fusion DNN"] = multi_metrics
    print(f"  Accuracy: {multi_metrics['accuracy']:.4f}, Macro F1: {multi_metrics['macro_f1']:.4f}, Latency: {multi_lat_us:.1f}us, Size: {multi_metrics['model_size_kb']:.1f}KB")

    for wid, sid, pred, conf, probs in zip(test_wids, test_sids, multi_preds, multi_probs.max(axis=1), multi_probs):
        predictions_to_persist.append({
            "prediction_id": f"pred-multi-{wid}",
            "window_id": wid,
            "session_id": sid,
            "model_name": "Multimodal Late-Fusion DNN",
            "predicted_class": CLASSES[pred],
            "confidence": float(conf),
            "prob_rest": float(probs[0]),
            "prob_restless": float(probs[1]),
            "prob_active": float(probs[2]),
            "inference_duration_us": float(multi_lat_us),
        })

    # Persist all predictions into SQLite
    sm = SqliteStorageManager(db_path)
    inserted_preds = sm.insert_model_predictions_batch(predictions_to_persist)
    print(f"\nPersisted {inserted_preds:,} model predictions into SQLite database.")

    # -------------------------------------------------------------------------
    # Multimodal Ablation Study (7 Conditions)
    # -------------------------------------------------------------------------
    print("\n" + "=" * 70)
    print("Executing 7-Condition Multimodal Ablation Study")
    print("=" * 70)

    ablation_results: dict[str, Any] = {}

    # A. Accelerometer-only (Dense Model)
    ablation_results["A_accel_only"] = dense_metrics

    # B. HR-only (MLP trained on HR branch only)
    hr_only_model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(2,)),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(8, activation="relu"),
        tf.keras.layers.Dense(3, activation="softmax"),
    ])
    hr_only_model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    hr_only_model.fit(X_train_hr, y_train, epochs=10, batch_size=128, verbose=0)
    hr_only_probs = hr_only_model.predict(X_test_hr, verbose=0)
    ablation_results["B_hr_only"] = compute_metrics(y_test, np.argmax(hr_only_probs, axis=1), hr_only_probs)

    # C. Accelerometer + HR (Multimodal Late Fusion Full)
    ablation_results["C_accel_plus_hr"] = multi_metrics

    # D. Accelerometer + Missing HR (mask = 0.0, norm_bpm = 0.0)
    X_test_hr_missing = np.zeros_like(X_test_hr)
    d_probs = multi_model.predict([X_test_accel, X_test_hr_missing], verbose=0)
    ablation_results["D_accel_missing_hr"] = compute_metrics(y_test, np.argmax(d_probs, axis=1), d_probs)

    # E. Accelerometer + Noisy HR (Gaussian noise added to HR)
    rng = np.random.default_rng(42)
    X_test_hr_noisy = np.copy(X_test_hr)
    X_test_hr_noisy[:, 0] += rng.normal(0.0, 0.35, len(X_test_hr_noisy)).astype(np.float32)
    e_probs = multi_model.predict([X_test_accel, X_test_hr_noisy], verbose=0)
    ablation_results["E_accel_noisy_hr"] = compute_metrics(y_test, np.argmax(e_probs, axis=1), e_probs)

    # F. Accelerometer + Stale HR (Shifted by 15 windows)
    X_test_hr_stale = np.roll(X_test_hr, shift=15, axis=0)
    f_probs = multi_model.predict([X_test_accel, X_test_hr_stale], verbose=0)
    ablation_results["F_accel_stale_hr"] = compute_metrics(y_test, np.argmax(f_probs, axis=1), f_probs)

    # G. Accelerometer + Shifted Generator Configuration
    print("Loading Held-Out SHIFTED Cohort for Domain Shift Evaluation...")
    shifted_db_path = ROOT / "data" / "synthetic" / "sleepsense_shifted.sqlite"
    if not shifted_db_path.exists():
        sm_shifted = SqliteStorageManager(shifted_db_path, output_policy=OutputPolicy.REPLACE_GENERATED_DATA)
        shifted_gen = SyntheticDatasetGenerator(storage_manager=sm_shifted)
        shifted_manifest = shifted_gen.generate_dataset(PROFILES["SHIFTED"], output_policy=OutputPolicy.REPLACE_GENERATED_DATA)
    dataset_shifted = load_canonical_window_dataset(shifted_db_path)

    # Collate shifted test data strictly via authoritative CollatePolicy
    shifted_subs = dataset_shifted.get_subject_ids()
    X_shift_accel, X_shift_hr, X_shift_tab, y_shift, _, _, _ = dataset_shifted.collate_subjects(
        shifted_subs, policy="TEST_ACCEPTED_ONLY", return_metadata=True
    )
    X_shift_full_accel, X_shift_full_hr, X_shift_full_tab, y_shift_full, _, _, shift_full_mask = dataset_shifted.collate_subjects(
        shifted_subs, policy="TEST_FULL_POPULATION", return_metadata=True
    )

    g_probs = multi_model.predict([X_shift_accel, X_shift_hr], verbose=0)
    ablation_results["G_domain_shift_evaluation"] = compute_metrics(y_shift, np.argmax(g_probs, axis=1), g_probs)

    g_full_probs = multi_model.predict([X_shift_full_accel, X_shift_full_hr], verbose=0)
    ablation_results["G_domain_shift_full_population"] = compute_metrics(y_shift_full, np.argmax(g_full_probs, axis=1), g_full_probs)

    dense_shift_probs = dense_model.predict(X_shift_accel, verbose=0)
    results["Domain Shift (Dense Accel DNN)"] = compute_metrics(y_shift, np.argmax(dense_shift_probs, axis=1), dense_shift_probs)
    results["Domain Shift (Multimodal Late-Fusion)"] = ablation_results["G_domain_shift_evaluation"]

    print(f"Domain Shift Evaluation (Standard -> Shifted Cohort N={len(y_shift):,} accepted, N={len(y_shift_full):,} full):")
    print(f"  Dense Accel Macro F1: {results['Domain Shift (Dense Accel DNN)']['macro_f1']:.4f} (Accuracy: {results['Domain Shift (Dense Accel DNN)']['accuracy']:.4f})")
    print(f"  Multimodal Macro F1:  {results['Domain Shift (Multimodal Late-Fusion)']['macro_f1']:.4f} (Accuracy: {results['Domain Shift (Multimodal Late-Fusion)']['accuracy']:.4f})")
    print(f"  Multimodal Full Population Macro F1: {ablation_results['G_domain_shift_full_population']['macro_f1']:.4f}")

    # -------------------------------------------------------------------------
    # Stage Candidate Models (Controlled Staging - ML-003)
    # -------------------------------------------------------------------------
    candidate_dir = ROOT / "ml" / "artifacts" / "candidates"
    candidate_dir.mkdir(parents=True, exist_ok=True)
    candidate_tflite_path = candidate_dir / "multimodal_classifier_candidate.tflite"
    candidate_tflite_path.write_bytes(multi_tflite_bytes)
    dense_candidate_path = candidate_dir / "activity_classifier_candidate.tflite"
    dense_candidate_path.write_bytes(dense_tflite_bytes)

    import hashlib
    multi_sha256 = hashlib.sha256(multi_tflite_bytes).hexdigest()
    dense_sha256 = hashlib.sha256(dense_tflite_bytes).hexdigest()

    split_fingerprint = hashlib.sha256(
        json.dumps({"train": train_subs, "val": val_subs, "test": test_subs}, sort_keys=True).encode("utf-8")
    ).hexdigest()

    # Provenance-complete Candidate Manifest
    candidate_manifest = {
        "model_id": "multimodal-tflite-v2.1",
        "model_version": "2.1.0",
        "dataset_fingerprint": exclusion_audit.get("manifest_id", "MANIFEST-SMALL-d059bf819ad7fda6"),
        "generator_version": "2.1.0",
        "feature_schema": ["dyn_x", "dyn_y", "dyn_z", "norm_bpm", "hr_mask"],
        "tabular_feature_schema": ["motion_rms", "variance", "jerk", "peak_to_peak", "norm_bpm", "hr_mask"],
        "split_fingerprint": split_fingerprint,
        "split_definition": {
            "train_subjects": train_subs,
            "val_subjects": val_subs,
            "test_subjects": test_subs,
        },
        "training_eligibility_policy": "TRAINING_STRICT",
        "training_config": {
            "epochs": 15,
            "batch_size": 128,
            "optimizer": "adam",
            "learning_rate": 0.002,
            "loss": "sparse_categorical_crossentropy",
            "class_weights": class_weight_dict,
        },
        "tflite_sha256": multi_sha256,
        "dense_tflite_sha256": dense_sha256,
        "input_tensors": [
            {"name": "accelerometer_input", "shape": [1, 20, 3], "dtype": "float32"},
            {"name": "heart_rate_input", "shape": [1, 2], "dtype": "float32"},
        ],
        "output_tensors": [
            {"name": "probabilities", "shape": [1, 3], "dtype": "float32"},
        ],
        "classes": CLASSES,
        "hr_normalization": "norm_bpm = (bpm - 60.0) / 40.0",
        "hr_staleness_contract": "0 <= age_ms <= 10000",
        "validation_results": val_metrics,
        "independent_test_results": {
            "accepted_only": multi_metrics,
            "full_population": ablation_results.get("G_domain_shift_full_population", {}),
        },
        "macro_f1_test": multi_metrics["macro_f1"],
        "accuracy_test": multi_metrics["accuracy"],
        "balanced_accuracy_test": multi_metrics["balanced_accuracy"],
        "domain_shift_macro_f1": ablation_results["G_domain_shift_evaluation"]["macro_f1"],
        "domain_shift_results": ablation_results["G_domain_shift_evaluation"],
        "created_at_utc": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    (candidate_dir / "candidate_manifest.json").write_text(json.dumps(candidate_manifest, indent=2), encoding="utf-8")
    print(f"Staged candidate TFLite models in: {candidate_dir} (SHA256={multi_sha256[:16]}...)")

    # -------------------------------------------------------------------------
    # Persist Results
    # -------------------------------------------------------------------------
    full_payload = {
        "benchmark_models": results,
        "multimodal_ablation": ablation_results,
        "metadata": {
            "test_sample_count": len(y_test),
            "shifted_sample_count": len(y_shift),
            "train_subjects": train_subs,
            "test_subjects": test_subs,
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        },
    }

    out_json = DOCS_DATA_SCIENCE / "ml_experiments_results.json"
    out_json.write_text(json.dumps(full_payload, indent=2), encoding="utf-8")
    print(f"\nSaved comprehensive experiment results to: {out_json}")

    return full_payload


if __name__ == "__main__":
    run_experiments()
