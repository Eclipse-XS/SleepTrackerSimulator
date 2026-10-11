"""Train and export the educational rest-movement classifier.

The dataset is deliberately synthetic. Independent simulated sessions are split
before windows are generated, so no session contributes to multiple splits.
Metrics therefore validate the generator/model pipeline only; they are not
evidence of physiological or clinical accuracy.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")

import numpy as np
import tensorflow as tf
from sklearn.metrics import classification_report, confusion_matrix

SEED = 6143
WINDOW = 20
CHANNELS = 3
SAMPLE_RATE_HZ = 20
LABELS = ["STILL", "MICRO_MOVEMENT", "PRONOUNCED_MOVEMENT"]
ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "app/src/main/assets/rest_movement_classifier.tflite"
RESULTS_PATH = ROOT / "ml/model_metrics.json"


def preprocess(windows: np.ndarray) -> np.ndarray:
    centered = windows - windows.mean(axis=1, keepdims=True)
    return np.clip(centered, -4.0, 4.0) / 4.0


def make_session(label: int, session_id: int, windows: int = 12) -> np.ndarray:
    rng = np.random.default_rng(SEED + label * 10_000 + session_id)
    total = windows * WINDOW
    t = np.arange(total, dtype=np.float32) / SAMPLE_RATE_HZ
    gravity = rng.uniform(-1.0, 1.0, size=3).astype(np.float32)
    gravity = gravity / np.linalg.norm(gravity) * 9.81
    drift = rng.uniform(-0.08, 0.08, size=(1, 3)).astype(np.float32) * t[:, None]
    noise_scale = [0.025, 0.055, 0.11][label]
    signal = gravity + drift + rng.normal(0.0, noise_scale, size=(total, 3))

    if label == 1:
        for _ in range(windows * 2):
            center = rng.integers(3, total - 3)
            width = rng.uniform(0.7, 1.6)
            amplitude = rng.uniform(0.18, 0.65)
            axis = rng.normal(size=3)
            axis /= np.linalg.norm(axis)
            idx = np.arange(total)
            pulse = amplitude * np.exp(-0.5 * ((idx - center) / width) ** 2)
            signal += pulse[:, None] * axis[None, :]
    elif label == 2:
        frequencies = rng.uniform(1.0, 3.8, size=3)
        amplitudes = rng.uniform(0.7, 2.2, size=3)
        phases = rng.uniform(0, 2 * np.pi, size=3)
        signal += amplitudes * np.sin(2 * np.pi * t[:, None] * frequencies + phases)
        for _ in range(windows):
            center = rng.integers(2, total - 2)
            signal[center - 1 : center + 2] += rng.normal(0, 1.0, size=(3, 3))

    return signal.reshape(windows, WINDOW, CHANNELS).astype(np.float32)


def dataset(session_ids: range) -> tuple[np.ndarray, np.ndarray]:
    xs, ys = [], []
    for label in range(len(LABELS)):
        for session_id in session_ids:
            windows = make_session(label, session_id)
            xs.append(windows)
            ys.extend([label] * len(windows))
    x = preprocess(np.concatenate(xs))
    y = np.asarray(ys, dtype=np.int64)
    order = np.random.default_rng(SEED + session_ids.start).permutation(len(y))
    return x[order], y[order]


def build_model() -> tf.keras.Model:
    tf.keras.utils.set_random_seed(SEED)
    inputs = tf.keras.Input(shape=(WINDOW, CHANNELS), name="accelerometer_window")
    x = tf.keras.layers.Conv1D(12, 5, activation="relu", name="temporal_features")(inputs)
    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dense(12, activation="relu")(x)
    outputs = tf.keras.layers.Dense(len(LABELS), activation="softmax", name="probabilities")(x)
    model = tf.keras.Model(inputs, outputs)
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def main() -> None:
    x_train, y_train = dataset(range(0, 40))
    x_val, y_val = dataset(range(40, 50))
    x_test, y_test = dataset(range(50, 60))
    model = build_model()
    history = model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=35,
        batch_size=64,
        verbose=2,
        callbacks=[tf.keras.callbacks.EarlyStopping(patience=6, restore_best_weights=True)],
    )

    predictions = model.predict(x_test, verbose=0).argmax(axis=1)
    report = classification_report(y_test, predictions, target_names=LABELS, output_dict=True)
    matrix = confusion_matrix(y_test, predictions).tolist()

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.write_bytes(tflite_model)

    interpreter = tf.lite.Interpreter(model_path=str(MODEL_PATH))
    interpreter.allocate_tensors()
    input_info = interpreter.get_input_details()[0]
    output_info = interpreter.get_output_details()[0]
    interpreter.set_tensor(input_info["index"], x_test[:1].astype(np.float32))
    interpreter.invoke()
    smoke_output = interpreter.get_tensor(output_info["index"])[0].tolist()

    results = {
        "seed": SEED,
        "data_source": "synthetic independent accelerometer sessions",
        "sample_rate_hz": SAMPLE_RATE_HZ,
        "window_samples": WINDOW,
        "input_shape": input_info["shape"].tolist(),
        "output_shape": output_info["shape"].tolist(),
        "input_dtype": str(input_info["dtype"]),
        "output_dtype": str(output_info["dtype"]),
        "labels": LABELS,
        "parameters": model.count_params(),
        "epochs_completed": len(history.history["loss"]),
        "final_train_accuracy": history.history["accuracy"][-1],
        "best_validation_accuracy": max(history.history["val_accuracy"]),
        "test_accuracy": float(np.mean(predictions == y_test)),
        "classification_report": report,
        "confusion_matrix": matrix,
        "conversion_smoke_output": smoke_output,
        "model_size_bytes": len(tflite_model),
        "sha256": hashlib.sha256(tflite_model).hexdigest(),
        "limitation": "Synthetic-data metrics do not establish performance on people or sleep stages.",
    }
    RESULTS_PATH.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
