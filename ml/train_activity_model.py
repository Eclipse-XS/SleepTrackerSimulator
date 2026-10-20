"""Train and export the deterministic educational motion classifier.

The dataset is synthetic and is suitable only for demonstrating an on-device
ML pipeline.  It is not a medical sleep-stage model.
"""

from __future__ import annotations

import json
import random
from pathlib import Path

import numpy as np
import tensorflow as tf

SEED = 4319
WINDOW = 20
CLASSES = ["REST", "RESTLESS", "ACTIVE"]
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
REPORT = ROOT / "docs" / "ml_evaluation.json"


def make_sample(label: int, rng: np.random.Generator) -> np.ndarray:
    t = np.linspace(0.0, 1.0, WINDOW, dtype=np.float32)
    gravity = np.array([0.0, 0.0, 9.81], dtype=np.float32)
    if label == 0:
        signal = rng.normal(0.0, 0.035, (WINDOW, 3))
    elif label == 1:
        frequency = rng.uniform(1.5, 3.0)
        wave = np.sin(2.0 * np.pi * frequency * t)[:, None]
        direction = rng.normal(size=(1, 3))
        direction /= np.linalg.norm(direction)
        signal = wave * direction * rng.uniform(0.25, 0.8)
        signal += rng.normal(0.0, 0.08, (WINDOW, 3))
    else:
        frequency = rng.uniform(3.0, 6.0)
        wave = np.sin(2.0 * np.pi * frequency * t)[:, None]
        direction = rng.normal(size=(1, 3))
        direction /= np.linalg.norm(direction)
        signal = wave * direction * rng.uniform(1.4, 3.2)
        signal += rng.normal(0.0, 0.35, (WINDOW, 3))
        for _ in range(rng.integers(1, 4)):
            signal[rng.integers(0, WINDOW)] += rng.normal(0.0, 2.2, 3)
    return (signal + gravity).astype(np.float32)


def dataset(count_per_class: int, seed: int) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    xs, ys = [], []
    for label in range(len(CLASSES)):
        for _ in range(count_per_class):
            xs.append(make_sample(label, rng))
            ys.append(label)
    order = rng.permutation(len(xs))
    return np.stack(xs)[order], np.asarray(ys, dtype=np.int32)[order]


def main() -> None:
    random.seed(SEED)
    np.random.seed(SEED)
    tf.random.set_seed(SEED)
    train_x, train_y = dataset(1200, SEED)
    test_x, test_y = dataset(300, SEED + 1)

    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(WINDOW, 3), name="accelerometer_window"),
        tf.keras.layers.Lambda(lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True)),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dense(len(CLASSES), activation="softmax", name="probabilities"),
    ])
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(train_x, train_y, epochs=18, batch_size=64, validation_split=0.2, verbose=2)
    loss, accuracy = model.evaluate(test_x, test_y, verbose=0)
    predicted = np.argmax(model.predict(test_x, verbose=0), axis=1)
    confusion = np.zeros((len(CLASSES), len(CLASSES)), dtype=int)
    for actual, guess in zip(test_y, predicted):
        confusion[actual, guess] += 1

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite = converter.convert()
    ASSETS.mkdir(parents=True, exist_ok=True)
    model_path = ASSETS / "activity_classifier.tflite"
    model_path.write_bytes(tflite)
    (ASSETS / "activity_labels.txt").write_text("\n".join(CLASSES) + "\n", encoding="utf-8")

    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    input_info = interpreter.get_input_details()[0]
    output_info = interpreter.get_output_details()[0]
    evidence = {
        "seed": SEED,
        "dataset": "deterministic synthetic accelerometer windows; educational only",
        "train_samples": int(train_x.shape[0]),
        "test_samples": int(test_x.shape[0]),
        "test_loss": float(loss),
        "test_accuracy": float(accuracy),
        "classes": CLASSES,
        "confusion_matrix_rows_actual_columns_predicted": confusion.tolist(),
        "input": {"shape": input_info["shape"].tolist(), "dtype": str(input_info["dtype"])},
        "output": {"shape": output_info["shape"].tolist(), "dtype": str(output_info["dtype"])},
        "model_bytes": len(tflite),
        "tensorflow_version": tf.__version__,
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
