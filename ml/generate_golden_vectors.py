"""Generates deterministic golden test vectors from the production TFLite model.

These golden vectors verify that on-device Kotlin/TFLite inference matches
the Python TensorFlow export to within floating-point precision tolerances.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "app" / "src" / "main" / "assets" / "activity_classifier.tflite"
LABELS_PATH = ROOT / "app" / "src" / "main" / "assets" / "activity_labels.txt"
OUTPUT_PATH = ROOT / "app" / "src" / "test" / "resources" / "ml_golden_vectors.json"

CLASSES = ["REST", "RESTLESS", "ACTIVE"]
WINDOW = 20


def generate_synthetic_window(label: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    t = np.linspace(0.0, 1.0, WINDOW, dtype=np.float32)
    gravity = np.array([0.0, 0.0, 9.81], dtype=np.float32)

    if label == 0:  # REST
        signal = rng.normal(0.0, 0.02, (WINDOW, 3))
    elif label == 1:  # RESTLESS
        freq = 2.0
        wave = np.sin(2.0 * np.pi * freq * t)[:, None]
        signal = wave * np.array([[0.5, 0.2, 0.1]]) + rng.normal(0.0, 0.05, (WINDOW, 3))
    else:  # ACTIVE
        freq = 4.5
        wave = np.sin(2.0 * np.pi * freq * t)[:, None]
        signal = wave * np.array([[2.2, 1.8, 0.8]]) + rng.normal(0.0, 0.3, (WINDOW, 3))

    return (signal + gravity).astype(np.float32)


def main() -> None:
    print(f"Loading production TFLite model from: {MODEL_PATH}")
    if not MODEL_PATH.exists():
        raise FileNotFoundError(f"Model not found at {MODEL_PATH}")

    interpreter = tf.lite.Interpreter(model_path=str(MODEL_PATH))
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()[0]
    output_details = interpreter.get_output_details()[0]

    vectors = []

    # 4 REST vectors, 4 RESTLESS vectors, 4 ACTIVE vectors
    test_cases = [
        (0, 101, "rest_calm_mattress"),
        (0, 102, "rest_steady_horizontal"),
        (0, 103, "rest_minimal_breathing_artifact"),
        (0, 104, "rest_deep_stillness"),
        (1, 201, "restless_small_twitch"),
        (1, 202, "restless_limb_shift"),
        (1, 203, "restless_periodic_turning"),
        (1, 204, "restless_posture_adjustment"),
        (2, 301, "active_rolling_over"),
        (2, 302, "active_getting_up"),
        (2, 303, "active_vigorous_body_movement"),
        (2, 304, "active_high_amplitude_jerk"),
    ]

    for label_idx, seed, name in test_cases:
        window_raw = generate_synthetic_window(label_idx, seed)

        # Run TFLite inference
        inp = np.expand_dims(window_raw, axis=0)
        interpreter.set_tensor(input_details["index"], inp)
        interpreter.invoke()
        probs = interpreter.get_tensor(output_details["index"])[0]

        pred_idx = int(np.argmax(probs))
        pred_label = CLASSES[pred_idx]
        confidence = float(probs[pred_idx])

        vectors.append({
            "name": name,
            "ground_truth_label": CLASSES[label_idx],
            "expected_predicted_class": pred_label,
            "expected_confidence": confidence,
            "expected_probabilities": {
                "REST": float(probs[0]),
                "RESTLESS": float(probs[1]),
                "ACTIVE": float(probs[2]),
            },
            "input_window": window_raw.tolist(),
        })

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps({
        "model_file": "activity_classifier.tflite",
        "tolerance": 1e-5,
        "sample_count": len(vectors),
        "vectors": vectors,
    }, indent=2), encoding="utf-8")

    print(f"Successfully generated {len(vectors)} golden vectors in: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
