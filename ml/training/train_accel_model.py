"""Train baseline and 1D CNN accelerometer models with MLflow and group-aware validation."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import mlflow
import numpy as np
import tensorflow as tf
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score

ROOT = Path(__file__).resolve().parents[2]
DOCS_ML = ROOT / "docs" / "ml"
WINDOW = 20
CLASSES = ["REST", "RESTLESS", "ACTIVE"]


def generate_session_dataset(
    n_sessions: int = 12,
    windows_per_session: int = 60,
    seed: int = 42,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Generates synthetic dataset tagged with session IDs to enable group-aware splitting."""
    rng = np.random.default_rng(seed)
    xs, ys, session_ids = [], [], []
    t = np.linspace(0.0, 1.0, WINDOW, dtype=np.float32)
    gravity = np.array([0.0, 0.0, 9.81], dtype=np.float32)

    for s_idx in range(n_sessions):
        sid = f"session-{s_idx:03d}"
        for _ in range(windows_per_session):
            p = rng.random()
            if p < 0.60:
                label = 0
                signal = rng.normal(0.0, 0.03, (WINDOW, 3))
            elif p < 0.85:
                label = 1
                freq = rng.uniform(1.5, 3.0)
                wave = np.sin(2.0 * np.pi * freq * t)[:, None]
                dir_vec = rng.normal(size=(1, 3))
                dir_vec /= np.linalg.norm(dir_vec)
                signal = wave * dir_vec * rng.uniform(0.3, 0.75) + rng.normal(0.0, 0.06, (WINDOW, 3))
            else:
                label = 2
                freq = rng.uniform(3.0, 6.0)
                wave = np.sin(2.0 * np.pi * freq * t)[:, None]
                dir_vec = rng.normal(size=(1, 3))
                dir_vec /= np.linalg.norm(dir_vec)
                signal = wave * dir_vec * rng.uniform(1.5, 3.0) + rng.normal(0.0, 0.3, (WINDOW, 3))

            xs.append((signal + gravity).astype(np.float32))
            ys.append(label)
            session_ids.append(sid)

    return np.array(xs), np.array(ys, dtype=np.int32), np.array(session_ids)


def build_dense_model() -> tf.keras.Model:
    """Builds standard production Dense model with dynamic centering layer."""
    return tf.keras.Sequential([
        tf.keras.layers.Input(shape=(WINDOW, 3), name="accelerometer_window"),
        tf.keras.layers.Lambda(lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True), name="remove_gravity"),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(32, activation="relu", name="dense_hidden"),
        tf.keras.layers.Dense(len(CLASSES), activation="softmax", name="probabilities"),
    ], name="sleepsense_dense_baseline")


def build_1d_cnn_model() -> tf.keras.Model:
    """Builds temporal 1D CNN feature extractor model."""
    return tf.keras.Sequential([
        tf.keras.layers.Input(shape=(WINDOW, 3), name="accelerometer_window"),
        tf.keras.layers.Lambda(lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True), name="remove_gravity"),
        tf.keras.layers.Conv1D(16, kernel_size=3, padding="same", activation="relu"),
        tf.keras.layers.MaxPooling1D(pool_size=2),
        tf.keras.layers.Conv1D(32, kernel_size=3, padding="same", activation="relu"),
        tf.keras.layers.GlobalAveragePooling1D(),
        tf.keras.layers.Dense(16, activation="relu"),
        tf.keras.layers.Dense(len(CLASSES), activation="softmax", name="probabilities"),
    ], name="sleepsense_1d_cnn")


def train_and_evaluate(
    model_type: str = "dense",
    epochs: int = 15,
    seed: int = 42,
    log_mlflow: bool = True,
) -> dict[str, any]:
    np.random.seed(seed)
    tf.random.set_seed(seed)

    xs, ys, sessions = generate_session_dataset(n_sessions=14, windows_per_session=70, seed=seed)

    # Group-Aware Holdout: 3 sessions for testing, 11 for training (zero data leakage)
    unique_sessions = np.unique(sessions)
    test_sessions = set(unique_sessions[:3])
    train_mask = ~np.isin(sessions, list(test_sessions))
    test_mask = np.isin(sessions, list(test_sessions))

    train_x, train_y = xs[train_mask], ys[train_mask]
    test_x, test_y = xs[test_mask], ys[test_mask]

    model = build_dense_model() if model_type == "dense" else build_1d_cnn_model()
    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )

    if log_mlflow:
        mlflow.set_experiment("sleepsense_accelerometer_classification")
        mlflow.start_run(run_name=f"{model_type}_group_split")
        mlflow.log_params({
            "model_type": model_type,
            "epochs": epochs,
            "seed": seed,
            "train_samples": len(train_x),
            "test_samples": len(test_x),
            "test_sessions": list(test_sessions),
        })

    model.fit(
        train_x,
        train_y,
        epochs=epochs,
        batch_size=64,
        validation_split=0.15,
        verbose=0,
    )

    test_preds_probs = model.predict(test_x, verbose=0)
    test_preds = np.argmax(test_preds_probs, axis=1)

    acc = float(accuracy_score(test_y, test_preds))
    f1 = float(f1_score(test_y, test_preds, average="macro"))
    cm = confusion_matrix(test_y, test_preds).tolist()

    metrics = {
        "model_type": model_type,
        "test_accuracy": acc,
        "test_f1_macro": f1,
        "confusion_matrix": cm,
    }

    if log_mlflow:
        mlflow.log_metrics({"test_accuracy": acc, "test_f1_macro": f1})
        mlflow.end_run()

    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="SleepSense Accelerometer Model Training")
    parser.add_argument("--model", choices=["dense", "cnn"], default="dense")
    parser.add_argument("--epochs", type=int, default=15)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--no-mlflow", action="store_true")

    args = parser.parse_args()
    print(f"Training {args.model.upper()} with group-aware session split...")
    results = train_and_evaluate(
        model_type=args.model,
        epochs=args.epochs,
        seed=args.seed,
        log_mlflow=not args.no_mlflow,
    )

    print(f"Results: Test Accuracy = {results['test_accuracy']:.4f}, F1-Macro = {results['test_f1_macro']:.4f}")
    print("Confusion Matrix:")
    print(np.array(results["confusion_matrix"]))

    DOCS_ML.mkdir(parents=True, exist_ok=True)
    out_file = DOCS_ML / f"train_{args.model}_results.json"
    out_file.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"Saved results to {out_file}")


if __name__ == "__main__":
    main()
