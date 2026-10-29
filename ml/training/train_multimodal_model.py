"""Multimodal Machine Learning Pipeline and Feature Ablation Study for SleepSense 2.0.

Evaluates late fusion of accelerometer motion features with BLE heart rate.
Conducts feature ablation to assess statistical gain and robustness to BLE signal loss.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.metrics import accuracy_score, f1_score

from ml.export.export_tflite import ASSETS, TfliteExporter

ROOT = Path(__file__).resolve().parents[2]
DOCS_ML = ROOT / "docs" / "ml"
WINDOW = 20
CLASSES = ["REST", "RESTLESS", "ACTIVE"]


def generate_multimodal_dataset(
    n_sessions: int = 15,
    windows_per_session: int = 80,
    missing_hr_rate: float = 0.15,
    seed: int = 42,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Generates synthetic paired accelerometer windows, HR features, labels, and session IDs."""
    rng = np.random.default_rng(seed)
    xs_accel, xs_hr, ys, session_ids = [], [], [], []
    t = np.linspace(0.0, 1.0, WINDOW, dtype=np.float32)
    gravity = np.array([0.0, 0.0, 9.81], dtype=np.float32)

    for s_idx in range(n_sessions):
        sid = f"session-multi-{s_idx:03d}"
        for _ in range(windows_per_session):
            p = rng.random()
            if p < 0.60:
                label = 0  # REST
                signal = rng.normal(0.0, 0.025, (WINDOW, 3))
                bpm = float(rng.normal(58.0, 3.5))
            elif p < 0.85:
                label = 1  # RESTLESS
                freq = rng.uniform(1.5, 3.0)
                wave = np.sin(2.0 * np.pi * freq * t)[:, None]
                dir_vec = rng.normal(size=(1, 3))
                dir_vec /= np.linalg.norm(dir_vec)
                signal = wave * dir_vec * rng.uniform(0.3, 0.7) + rng.normal(0.0, 0.06, (WINDOW, 3))
                bpm = float(rng.normal(68.0, 4.5))
            else:
                label = 2  # ACTIVE
                freq = rng.uniform(3.0, 6.0)
                wave = np.sin(2.0 * np.pi * freq * t)[:, None]
                dir_vec = rng.normal(size=(1, 3))
                dir_vec /= np.linalg.norm(dir_vec)
                signal = wave * dir_vec * rng.uniform(1.5, 3.0) + rng.normal(0.0, 0.3, (WINDOW, 3))
                bpm = float(rng.normal(86.0, 7.0))

            accel_window = (signal + gravity).astype(np.float32)

            # Simulated missing BLE connection
            if rng.random() < missing_hr_rate:
                norm_bpm = 0.0
                hr_mask = 0.0
            else:
                norm_bpm = (bpm - 60.0) / 40.0  # Normalized around rest baseline
                hr_mask = 1.0

            xs_accel.append(accel_window)
            xs_hr.append([norm_bpm, hr_mask])
            ys.append(label)
            session_ids.append(sid)

    return (
        np.array(xs_accel, dtype=np.float32),
        np.array(xs_hr, dtype=np.float32),
        np.array(ys, dtype=np.int32),
        np.array(session_ids),
    )


def build_multimodal_fusion_model() -> tf.keras.Model:
    """Builds two-branch multimodal neural network with dynamic sensor fusion."""
    # Accelerometer Branch
    accel_in = tf.keras.layers.Input(shape=(WINDOW, 3), name="accelerometer_input")
    centered_accel = tf.keras.layers.Lambda(
        lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True),
        name="remove_gravity"
    )(accel_in)
    accel_flat = tf.keras.layers.Flatten()(centered_accel)
    accel_emb = tf.keras.layers.Dense(24, activation="relu", name="motion_embedding")(accel_flat)

    # Heart Rate & Mask Branch
    hr_in = tf.keras.layers.Input(shape=(2,), name="heart_rate_input")  # [norm_bpm, hr_mask]
    hr_emb = tf.keras.layers.Dense(8, activation="relu", name="hr_embedding")(hr_in)

    # Fusion
    fusion = tf.keras.layers.Concatenate(name="sensor_fusion")([accel_emb, hr_emb])
    fused_dense = tf.keras.layers.Dense(16, activation="relu", name="fused_dense")(fusion)
    outputs = tf.keras.layers.Dense(len(CLASSES), activation="softmax", name="probabilities")(fused_dense)

    model = tf.keras.Model(inputs=[accel_in, hr_in], outputs=outputs, name="multimodal_sleep_classifier")
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def build_accel_only_model() -> tf.keras.Model:
    """Baseline accelerometer-only model for ablation."""
    accel_in = tf.keras.layers.Input(shape=(WINDOW, 3), name="accelerometer_input")
    centered_accel = tf.keras.layers.Lambda(
        lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True),
        name="remove_gravity"
    )(accel_in)
    accel_flat = tf.keras.layers.Flatten()(centered_accel)
    accel_emb = tf.keras.layers.Dense(32, activation="relu")(accel_flat)
    outputs = tf.keras.layers.Dense(len(CLASSES), activation="softmax", name="probabilities")(accel_emb)

    model = tf.keras.Model(inputs=accel_in, outputs=outputs, name="accel_only_classifier")
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def run_ablation_study(seed: int = 42) -> dict[str, any]:
    """Runs systematic ablation comparing Accel-only vs Multimodal vs Multimodal without HR."""
    xs_accel, xs_hr, ys, sessions = generate_multimodal_dataset(seed=seed)

    # Group split by session
    unique_sessions = np.unique(sessions)
    test_sessions = set(unique_sessions[:3])
    train_mask = ~np.isin(sessions, list(test_sessions))
    test_mask = np.isin(sessions, list(test_sessions))

    train_accel, test_accel = xs_accel[train_mask], xs_accel[test_mask]
    train_hr, test_hr = xs_hr[train_mask], xs_hr[test_mask]
    train_y, test_y = ys[train_mask], ys[test_mask]

    # Model 1: Accel Only
    m_accel = build_accel_only_model()
    m_accel.fit(train_accel, train_y, epochs=15, batch_size=64, verbose=0)
    preds_accel = np.argmax(m_accel.predict(test_accel, verbose=0), axis=1)
    acc_accel = float(accuracy_score(test_y, preds_accel))
    f1_accel = float(f1_score(test_y, preds_accel, average="macro"))

    # Model 2: Multimodal (with available HR)
    m_multi = build_multimodal_fusion_model()
    m_multi.fit([train_accel, train_hr], train_y, epochs=15, batch_size=64, verbose=0)
    preds_multi = np.argmax(m_multi.predict([test_accel, test_hr], verbose=0), axis=1)
    acc_multi = float(accuracy_score(test_y, preds_multi))
    f1_multi = float(f1_score(test_y, preds_multi, average="macro"))

    # Model 3: Multimodal when BLE band is disconnected (mask = 0)
    test_hr_zeroed = np.zeros_like(test_hr)
    preds_multi_nohr = np.argmax(m_multi.predict([test_accel, test_hr_zeroed], verbose=0), axis=1)
    acc_multi_nohr = float(accuracy_score(test_y, preds_multi_nohr))
    f1_multi_nohr = float(f1_score(test_y, preds_multi_nohr, average="macro"))

    # Model 4: Heuristic Baseline
    heuristic_preds = []
    for w in test_accel:
        dyn = w - np.mean(w, axis=0, keepdims=True)
        mags = np.linalg.norm(dyn, axis=1)
        rms = float(np.sqrt(np.mean(mags ** 2)))
        var = float(np.var(mags))
        if rms < 0.10 and var < 0.015:
            heuristic_preds.append(0)
        elif rms < 0.85:
            heuristic_preds.append(1)
        else:
            heuristic_preds.append(2)
    acc_heur = float(accuracy_score(test_y, heuristic_preds))
    f1_heur = float(f1_score(test_y, heuristic_preds, average="macro"))

    ablation_results = {
        "dataset": {
            "total_windows": len(ys),
            "test_windows": len(test_y),
            "test_sessions": list(test_sessions),
        },
        "models": {
            "heuristic_baseline": {
                "accuracy": acc_heur,
                "f1_macro": f1_heur,
                "description": "Rule-based RMS/variance heuristic fallback",
            },
            "accel_only_dense": {
                "accuracy": acc_accel,
                "f1_macro": f1_accel,
                "description": "Single-sensor accelerometer dense classifier",
            },
            "multimodal_fusion": {
                "accuracy": acc_multi,
                "f1_macro": f1_multi,
                "description": "Multimodal late fusion of accelerometer and heart rate",
            },
            "multimodal_disconnected_band": {
                "accuracy": acc_multi_nohr,
                "f1_macro": f1_multi_nohr,
                "description": "Multimodal model evaluated with HR mask=0 (band disconnected)",
            },
        },
        "findings": {
            "hr_accuracy_gain": float(acc_multi - acc_accel),
            "graceful_degradation_delta": float(acc_multi - acc_multi_nohr),
            "physiological_validity_note": (
                "CRITICAL: The heart rate stream used in these experiments was generated via synthetic "
                "or simulated BLE broadcasts (VirtualSleepBand). Simulated heart rate does NOT constitute "
                "proof of clinical efficacy on real human subjects. Physical validation on real human "
                "physiological data remains required before any medical or clinical claims can be made."
            ),
        },
    }

    return ablation_results


def train_and_export_multimodal_model(seed: int = 42) -> Path:
    """Trains the multimodal model on synthetic session data and exports to TFLite."""
    xs_accel, xs_hr, ys, sessions = generate_multimodal_dataset(n_sessions=25, windows_per_session=100, seed=seed)

    unique_sessions = np.unique(sessions)
    test_sessions = set(unique_sessions[:5])
    train_mask = ~np.isin(sessions, list(test_sessions))
    test_mask = np.isin(sessions, list(test_sessions))

    train_accel, test_accel = xs_accel[train_mask], xs_accel[test_mask]
    train_hr, test_hr = xs_hr[train_mask], xs_hr[test_mask]
    train_y, test_y = ys[train_mask], ys[test_mask]

    model = build_multimodal_fusion_model()
    model.fit(
        [train_accel, train_hr],
        train_y,
        validation_data=([test_accel, test_hr], test_y),
        epochs=20,
        batch_size=64,
        verbose=1,
    )

    out_tflite = ASSETS / "multimodal_classifier.tflite"
    print(f"\nExporting multimodal TFLite model to: {out_tflite}")
    report = TfliteExporter.export_and_verify(
        model=model,
        output_path=out_tflite,
        golden_inputs=[test_accel[:100], test_hr[:100]],
        tolerance=1e-4,
    )
    print(f"Parity verification report: {report}")

    # Generate multimodal golden vectors for Android tests
    golden_test_path = ROOT / "app" / "src" / "test" / "resources" / "ml_multimodal_golden_vectors.json"
    interpreter = tf.lite.Interpreter(model_path=str(out_tflite))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()[0]

    accel_detail = next(d for d in input_details if tuple(d["shape"][1:]) == (WINDOW, 3))
    hr_detail = next(d for d in input_details if tuple(d["shape"][1:]) == (2,))

    golden_cases = []
    for idx in range(min(12, len(test_y))):
        sample_accel = test_accel[idx]
        sample_hr = test_hr[idx]
        gt_label = CLASSES[test_y[idx]]

        interpreter.set_tensor(accel_detail["index"], np.expand_dims(sample_accel, axis=0))
        interpreter.set_tensor(hr_detail["index"], np.expand_dims(sample_hr, axis=0))
        interpreter.invoke()
        probs = interpreter.get_tensor(output_details["index"])[0]

        pred_idx = int(np.argmax(probs))
        golden_cases.append({
            "sample_index": idx,
            "ground_truth_label": gt_label,
            "expected_predicted_class": CLASSES[pred_idx],
            "expected_confidence": float(probs[pred_idx]),
            "expected_probabilities": {
                "REST": float(probs[0]),
                "RESTLESS": float(probs[1]),
                "ACTIVE": float(probs[2]),
            },
            "norm_bpm": float(sample_hr[0]),
            "hr_mask": float(sample_hr[1]),
            "input_window": sample_accel.tolist(),
        })

    golden_test_path.parent.mkdir(parents=True, exist_ok=True)
    golden_test_path.write_text(json.dumps({
        "model_file": "multimodal_classifier.tflite",
        "description": "Golden test vectors for on-device multimodal TFLite numerical parity",
        "golden_vectors": golden_cases,
    }, indent=2), encoding="utf-8")
    print(f"Generated golden vectors at: {golden_test_path}")

    return out_tflite


def main() -> None:
    print("=" * 65)
    print("SleepSense 2.0: Multimodal ML & Feature Ablation Study")
    print("=" * 65)

    results = run_ablation_study(seed=42)

    print("\nAblation Results Summary:")
    for name, m in results["models"].items():
        print(f"  - {name:30s}: Acc = {m['accuracy']:.4f}, F1 = {m['f1_macro']:.4f}")

    print("\nStatistical Analysis:")
    f = results["findings"]
    print(f"  HR Gain vs Accel-only: {f['hr_accuracy_gain']:+.4f}")
    print(f"  Graceful Degradation (band disconnect): {f['graceful_degradation_delta']:+.4f}")
    print(f"\n{f['physiological_validity_note']}")

    DOCS_ML.mkdir(parents=True, exist_ok=True)
    report_file = DOCS_ML / "multimodal_ablation_study.json"
    report_file.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nSaved report to: {report_file}")

    print("\nTraining and exporting production multimodal TFLite artifact...")
    train_and_export_multimodal_model(seed=42)


if __name__ == "__main__":
    main()
