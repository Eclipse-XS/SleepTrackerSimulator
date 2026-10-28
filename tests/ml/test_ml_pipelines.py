"""Unit tests for SleepSense ML pipelines, TFLite evaluation, and export parity."""

from __future__ import annotations

import tempfile
from pathlib import Path

import numpy as np
import tensorflow as tf

from ml.evaluation.evaluate_tflite_artifact import DeployedTfliteEvaluator
from ml.export.export_tflite import TfliteExporter
from ml.training.train_multimodal_model import build_multimodal_fusion_model


def test_deployed_tflite_artifact_integrity() -> None:
    evaluator = DeployedTfliteEvaluator()
    assert evaluator.input_shape == [1, 20, 3]
    assert evaluator.output_shape == [1, 3]
    assert evaluator.labels == ["REST", "RESTLESS", "ACTIVE"]

    # Test static window
    static_window = np.zeros((20, 3), dtype=np.float32)
    static_window[:, 2] = 9.81
    pred_class, probs = evaluator.predict_window(static_window)
    assert pred_class == 0  # REST
    assert probs[0] > 0.7  # High confidence REST


def test_tflite_export_and_numerical_parity() -> None:
    # Build a tiny deterministic Keras model
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(20, 3)),
        tf.keras.layers.Flatten(),
        tf.keras.layers.Dense(8, activation="relu"),
        tf.keras.layers.Dense(3, activation="softmax"),
    ])

    golden_vectors = np.random.default_rng(42).normal(0.0, 1.0, (10, 20, 3)).astype(np.float32)

    with tempfile.TemporaryDirectory() as tmp_dir:
        tflite_out = Path(tmp_dir) / "test_model.tflite"
        report = TfliteExporter.export_and_verify(
            model=model,
            output_path=tflite_out,
            golden_inputs=golden_vectors,
            tolerance=1e-4,
        )

        assert tflite_out.exists()
        assert report["numerical_parity_passed"] is True
        assert report["max_absolute_error"] < 1e-4


def test_multimodal_model_shapes_and_forward_pass() -> None:
    model = build_multimodal_fusion_model()
    accel_in = np.zeros((4, 20, 3), dtype=np.float32)
    hr_in = np.zeros((4, 2), dtype=np.float32)

    preds = model.predict([accel_in, hr_in], verbose=0)
    assert preds.shape == (4, 3)
    # Check probabilities normalize to 1.0
    row_sums = np.sum(preds, axis=1)
    assert np.allclose(row_sums, 1.0, atol=1e-5)
