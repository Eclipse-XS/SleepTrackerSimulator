"""Export Keras models to TFLite with numerical parity and golden vector verification."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
DOCS_ML = ROOT / "docs" / "ml"


class TfliteExporter:
    """Exports Keras models to TFLite and verifies numerical precision against golden vectors."""

    @staticmethod
    def export_and_verify(
        model: tf.keras.Model,
        output_path: Path | str,
        golden_inputs: np.ndarray | list[np.ndarray],
        quantize_dynamic_range: bool = False,
        tolerance: float = 1e-4,
    ) -> dict[str, any]:
        """Converts model to TFLite and verifies that max absolute difference on golden_inputs is <= tolerance."""
        out_file = Path(output_path)
        out_file.parent.mkdir(parents=True, exist_ok=True)

        # 1. Convert
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        if quantize_dynamic_range:
            converter.optimizations = [tf.lite.Optimize.DEFAULT]

        tflite_bytes = converter.convert()
        out_file.write_bytes(tflite_bytes)

        # 2. Compute Keras baseline predictions
        keras_outputs = model.predict(golden_inputs, verbose=0)

        # 3. Compute TFLite predictions via Interpreter
        interpreter = tf.lite.Interpreter(model_content=tflite_bytes)
        interpreter.allocate_tensors()
        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()[0]

        is_multi_input = isinstance(golden_inputs, (list, tuple))
        num_samples = len(golden_inputs[0]) if is_multi_input else len(golden_inputs)

        # For multi-input, map model inputs to interpreter inputs by shape or name
        tflite_outputs = []
        for i in range(num_samples):
            if is_multi_input:
                for k, g_in in enumerate(golden_inputs):
                    # Match input_detail with shape
                    sample_k = np.expand_dims(g_in[i], axis=0).astype(np.float32)
                    matched_detail = None
                    for detail in input_details:
                        if tuple(detail["shape"][1:]) == tuple(sample_k.shape[1:]):
                            matched_detail = detail
                            break
                    if matched_detail is None:
                        matched_detail = input_details[k]
                    interpreter.set_tensor(matched_detail["index"], sample_k)
            else:
                x_expanded = np.expand_dims(golden_inputs[i], axis=0).astype(input_details[0]["dtype"])
                interpreter.set_tensor(input_details[0]["index"], x_expanded)

            interpreter.invoke()
            pred = interpreter.get_tensor(output_details["index"])[0]
            tflite_outputs.append(pred)

        tflite_outputs = np.array(tflite_outputs, dtype=np.float32)

        # 4. Measure numerical difference
        abs_diff = np.abs(keras_outputs - tflite_outputs)
        max_diff = float(np.max(abs_diff))
        mean_diff = float(np.mean(abs_diff))
        parity_passed = max_diff <= tolerance if not quantize_dynamic_range else max_diff <= 0.05

        verification_report = {
            "model_size_bytes": len(tflite_bytes),
            "quantized_dynamic_range": quantize_dynamic_range,
            "golden_sample_count": num_samples,
            "max_absolute_error": max_diff,
            "mean_absolute_error": mean_diff,
            "tolerance": tolerance,
            "numerical_parity_passed": parity_passed,
        }

        if not parity_passed:
            raise ValueError(
                f"Numerical parity check failed: max error {max_diff:.6f} exceeded tolerance {tolerance}"
            )

        return verification_report
