"""Train baseline and 1D CNN accelerometer models with MLflow and disjoint group splits.

Auxiliary synthetic session trainer (ML-002): splits train / validation / test by ``session_id`` (or by
``subject_id`` when subject identifiers are supplied) instead of Keras ``validation_split``, guaranteeing
zero session overlap between any two splits. For the canonical subject-independent benchmark see
:mod:`ml.experiments.runner`.
"""

from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import mlflow
import numpy as np
import tensorflow as tf
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score

ROOT = Path(__file__).resolve().parents[2]
DOCS_ML = ROOT / "docs" / "ml"
WINDOW = 20
CLASSES = ["REST", "RESTLESS", "ACTIVE"]


@dataclass(frozen=True)
class GroupSplitResult:
    """Disjoint group-aware train / validation / test split allocation."""

    train_mask: np.ndarray
    val_mask: np.ndarray
    test_mask: np.ndarray
    train_sessions: frozenset[str]
    val_sessions: frozenset[str]
    test_sessions: frozenset[str]
    train_groups: tuple[str, ...]
    val_groups: tuple[str, ...]
    test_groups: tuple[str, ...]
    group_kind: str


def split_by_group(
    session_ids: Sequence[str] | np.ndarray,
    subject_ids: Sequence[str] | np.ndarray | None = None,
    val_ratio: float = 0.20,
    test_ratio: float = 0.20,
    seed: int = 42,
) -> GroupSplitResult:
    """Deterministic disjoint train/validation/test split by session (or subject when available)."""
    sessions_arr = np.asarray(session_ids, dtype=str)
    if len(sessions_arr) == 0:
        raise ValueError("Cannot split an empty dataset (0 rows)")

    if subject_ids is not None:
        groups_arr = np.asarray(subject_ids, dtype=str)
        if len(groups_arr) != len(sessions_arr):
            raise ValueError("subject_ids and session_ids must have identical length")
        # Verify no session spans multiple subjects
        sess_to_subj: dict[str, str] = {}
        for sid, sub in zip(sessions_arr.tolist(), groups_arr.tolist()):
            if sid in sess_to_subj and sess_to_subj[sid] != sub:
                raise ValueError(f"Session {sid!r} is assigned to multiple subjects: {sess_to_subj[sid]!r}, {sub!r}")
            sess_to_subj[sid] = sub
        group_kind = "subject_id"
    else:
        groups_arr = sessions_arr
        group_kind = "session_id"

    unique_groups = sorted(set(groups_arr.tolist()))
    n_groups = len(unique_groups)
    if n_groups < 3:
        raise ValueError(
            f"At least 3 distinct {group_kind} groups are required for disjoint train/validation/test splits; "
            f"got {n_groups} ({unique_groups})."
        )
    if val_ratio <= 0 or test_ratio <= 0 or (val_ratio + test_ratio) >= 1.0:
        raise ValueError(f"Invalid split ratios val_ratio={val_ratio}, test_ratio={test_ratio}")

    rng = np.random.default_rng(seed)
    shuffled = rng.permutation(unique_groups).tolist()

    n_test = max(1, round(n_groups * test_ratio))
    n_val = max(1, round(n_groups * val_ratio))
    if n_test + n_val >= n_groups:
        n_test = 1
        n_val = 1
    n_train = n_groups - n_test - n_val
    if n_train < 1 or n_val < 1 or n_test < 1:
        raise ValueError(
            f"Insufficient groups ({n_groups}) to allocate non-empty train/val/test splits "
            f"(train={n_train}, val={n_val}, test={n_test})."
        )

    test_groups = tuple(sorted(shuffled[:n_test]))
    val_groups = tuple(sorted(shuffled[n_test : n_test + n_val]))
    train_groups = tuple(sorted(shuffled[n_test + n_val :]))

    train_mask = np.isin(groups_arr, train_groups)
    val_mask = np.isin(groups_arr, val_groups)
    test_mask = np.isin(groups_arr, test_groups)

    train_sessions = frozenset(sessions_arr[train_mask].tolist())
    val_sessions = frozenset(sessions_arr[val_mask].tolist())
    test_sessions = frozenset(sessions_arr[test_mask].tolist())

    if (train_sessions & val_sessions) or (train_sessions & test_sessions) or (val_sessions & test_sessions):
        raise AssertionError("Session leakage detected across train/validation/test splits")

    return GroupSplitResult(
        train_mask=train_mask,
        val_mask=val_mask,
        test_mask=test_mask,
        train_sessions=train_sessions,
        val_sessions=val_sessions,
        test_sessions=test_sessions,
        train_groups=train_groups,
        val_groups=val_groups,
        test_groups=test_groups,
        group_kind=group_kind,
    )


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
) -> dict[str, Any]:
    np.random.seed(seed)
    tf.random.set_seed(seed)

    xs, ys, sessions = generate_session_dataset(n_sessions=14, windows_per_session=70, seed=seed)
    split = split_by_group(sessions, val_ratio=0.20, test_ratio=0.20, seed=seed)

    train_x, train_y = xs[split.train_mask], ys[split.train_mask]
    val_x, val_y = xs[split.val_mask], ys[split.val_mask]
    test_x, test_y = xs[split.test_mask], ys[split.test_mask]

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
            "split_group_kind": split.group_kind,
            "train_samples": len(train_x),
            "val_samples": len(val_x),
            "test_samples": len(test_x),
            "train_sessions": sorted(split.train_sessions),
            "val_sessions": sorted(split.val_sessions),
            "test_sessions": sorted(split.test_sessions),
        })

    model.fit(
        train_x,
        train_y,
        epochs=epochs,
        batch_size=64,
        validation_data=(val_x, val_y),
        verbose=0,
    )

    test_preds_probs = model.predict(test_x, verbose=0)
    test_preds = np.argmax(test_preds_probs, axis=1)

    acc = float(accuracy_score(test_y, test_preds))
    f1 = float(f1_score(test_y, test_preds, average="macro"))
    cm = confusion_matrix(test_y, test_preds).tolist()

    metrics = {
        "model_type": model_type,
        "split_group_kind": split.group_kind,
        "train_sessions": sorted(split.train_sessions),
        "val_sessions": sorted(split.val_sessions),
        "test_sessions": sorted(split.test_sessions),
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
