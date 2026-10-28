"""Evaluation of the exact deployed TFLite artifact on Android."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
DOCS_ML = ROOT / "docs" / "ml"
MODEL_PATH = ASSETS / "activity_classifier.tflite"
LABELS_PATH = ASSETS / "activity_labels.txt"

WINDOW = 20
CLASSES = ["REST", "RESTLESS", "ACTIVE"]


def generate_evaluation_dataset(count_per_class: int = 250, seed: int = 9821) -> tuple[np.ndarray, np.ndarray]:
    """Generates synthetic test windows with ground truth labels."""
    rng = np.random.default_rng(seed)
    xs, ys = [], []
    t = np.linspace(0.0, 1.0, WINDOW, dtype=np.float32)
    gravity = np.array([0.0, 0.0, 9.81], dtype=np.float32)

    for label in range(len(CLASSES)):
        for _ in range(count_per_class):
            if label == 0:  # REST
                signal = rng.normal(0.0, 0.025, (WINDOW, 3))
            elif label == 1:  # RESTLESS
                freq = rng.uniform(1.5, 3.0)
                wave = np.sin(2.0 * np.pi * freq * t)[:, None]
                direction = rng.normal(size=(1, 3))
                direction /= np.linalg.norm(direction)
                signal = wave * direction * rng.uniform(0.3, 0.75) + rng.normal(0.0, 0.06, (WINDOW, 3))
            else:  # ACTIVE
                freq = rng.uniform(3.0, 6.0)
                wave = np.sin(2.0 * np.pi * freq * t)[:, None]
                direction = rng.normal(size=(1, 3))
                direction /= np.linalg.norm(direction)
                signal = wave * direction * rng.uniform(1.5, 3.0) + rng.normal(0.0, 0.3, (WINDOW, 3))
                for _ in range(rng.integers(1, 3)):
                    signal[rng.integers(0, WINDOW)] += rng.normal(0.0, 2.0, 3)

            xs.append((signal + gravity).astype(np.float32))
            ys.append(label)

    order = rng.permutation(len(xs))
    return np.stack(xs)[order], np.asarray(ys, dtype=np.int32)[order]


class DeployedTfliteEvaluator:
    """Evaluates the exact Android TFLite artifact binary."""

    def __init__(self, model_path: Path = MODEL_PATH, labels_path: Path = LABELS_PATH):
        if not model_path.exists():
            raise FileNotFoundError(f"TFLite model not found at: {model_path}")

        self.model_path = model_path
        self.labels = labels_path.read_text(encoding="utf-8").strip().splitlines()
        self.interpreter = tf.lite.Interpreter(model_path=str(model_path))
        self.interpreter.allocate_tensors()

        input_details = self.interpreter.get_input_details()[0]
        output_details = self.interpreter.get_output_details()[0]

        self.input_idx = input_details["index"]
        self.output_idx = output_details["index"]
        self.input_shape = input_details["shape"].tolist()
        self.output_shape = output_details["shape"].tolist()

    def predict_window(self, window: np.ndarray) -> tuple[int, np.ndarray]:
        """Runs single-window inference through the TFLite interpreter."""
        input_tensor = np.expand_dims(window.astype(np.float32), axis=0)
        self.interpreter.set_tensor(self.input_idx, input_tensor)
        self.interpreter.invoke()
        probs = self.interpreter.get_tensor(self.output_idx)[0]
        pred_class = int(np.argmax(probs))
        return pred_class, probs

    def evaluate(self, test_x: np.ndarray, test_y: np.ndarray) -> dict[str, any]:
        """Evaluates model accuracy, confusion matrix, and per-class metrics on test data."""
        predictions = []
        probabilities = []

        for sample in test_x:
            pred_class, probs = self.predict_window(sample)
            predictions.append(pred_class)
            probabilities.append(probs)

        preds = np.array(predictions, dtype=np.int32)
        acc = float(accuracy_score(test_y, preds))
        f1_macro = float(f1_score(test_y, preds, average="macro"))
        cm = confusion_matrix(test_y, preds).tolist()
        report = classification_report(test_y, preds, target_names=self.labels, output_dict=True)

        return {
            "model_path": str(self.model_path),
            "model_size_bytes": self.model_path.stat().st_size,
            "input_shape": self.input_shape,
            "output_shape": self.output_shape,
            "labels": self.labels,
            "test_sample_count": len(test_y),
            "accuracy": acc,
            "f1_macro": f1_macro,
            "confusion_matrix": cm,
            "classification_report": report,
        }

    def evaluate_edge_cases(self) -> dict[str, any]:
        """Audits model predictions against physical edge cases."""
        results = {}

        # 1. Perfectly static phone with standard gravity
        static_window = np.zeros((WINDOW, 3), dtype=np.float32)
        static_window[:, 2] = 9.81
        pred_static, probs_static = self.predict_window(static_window)
        results["static_gravity_prediction"] = self.labels[pred_static]
        results["static_gravity_probs"] = probs_static.tolist()

        # 2. Inverted phone (face down: z = -9.81)
        inverted_window = np.zeros((WINDOW, 3), dtype=np.float32)
        inverted_window[:, 2] = -9.81
        pred_inverted, probs_inverted = self.predict_window(inverted_window)
        results["inverted_phone_prediction"] = self.labels[pred_inverted]
        results["inverted_phone_probs"] = probs_inverted.tolist()

        # 3. Phone on side (y = 9.81)
        side_window = np.zeros((WINDOW, 3), dtype=np.float32)
        side_window[:, 1] = 9.81
        pred_side, _ = self.predict_window(side_window)
        results["side_phone_prediction"] = self.labels[pred_side]

        # 4. High energy movement (vigorous active shaking)
        rng = np.random.default_rng(123)
        active_window = rng.normal(0.0, 3.5, (WINDOW, 3)).astype(np.float32)
        active_window[:, 2] += 9.81
        pred_active, _ = self.predict_window(active_window)
        results["high_energy_active_prediction"] = self.labels[pred_active]

        return results


def main() -> None:
    print(f"Loading deployed artifact: {MODEL_PATH}")
    evaluator = DeployedTfliteEvaluator()
    print(f"Model input shape: {evaluator.input_shape}, output shape: {evaluator.output_shape}")
    print(f"Labels: {evaluator.labels}")

    test_x, test_y = generate_evaluation_dataset(count_per_class=300, seed=12345)
    print(f"Generated evaluation dataset: {len(test_x)} windows across 3 classes.")

    eval_results = evaluator.evaluate(test_x, test_y)
    print(f"Evaluation Accuracy: {eval_results['accuracy']:.4f}, F1-Macro: {eval_results['f1_macro']:.4f}")
    print("Confusion Matrix (rows=actual, cols=pred):")
    print(np.array(eval_results["confusion_matrix"]))

    edge_cases = evaluator.evaluate_edge_cases()
    print("\nEdge Case Audit:")
    for k, v in edge_cases.items():
        print(f"  {k}: {v}")

    DOCS_ML.mkdir(parents=True, exist_ok=True)
    report_file = DOCS_ML / "tflite_artifact_evaluation.json"
    full_report = {
        **eval_results,
        "edge_cases": edge_cases,
    }
    report_file.write_text(json.dumps(full_report, indent=2), encoding="utf-8")
    print(f"\nSaved evaluation report to: {report_file}")


if __name__ == "__main__":
    main()
