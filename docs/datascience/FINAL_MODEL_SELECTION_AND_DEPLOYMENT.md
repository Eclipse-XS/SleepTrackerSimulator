# SleepSense 2.0 — Final Model Selection & Edge Deployment Report

## 1. Executive Summary & Objective

This document formalizes the final machine learning model selection for on-device deployment in SleepSense 2.0. The decision is grounded in empirical cross-subject generalization (6-fold group cross-validation), inference latency on mobile CPU, memory footprint, expected calibration error (ECE), and resilience to missing Bluetooth Low Energy (BLE) heart-rate telemetry.

---

## 2. Comprehensive Model Comparison Matrix

The candidate architectures were evaluated across 6 subject-independent folds ($N=12$ subjects, $64,780$ windows), with out-of-sample held-out testing and latency profiling:

| Evaluation Dimension | Physical Rule Heuristic | Logistic Regression | Random Forest (100 Trees) | Accel-Only DNN | Multimodal DNN (Unweighted) | Multimodal DNN (Balanced Weights) |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| **Mean Macro F1 (6-Fold CV)** | $0.6578 \pm 0.0659$ | $0.7915 \pm 0.0959$ | **$0.8358 \pm 0.0783$** | $0.8227 \pm 0.0947$ | $0.8325 \pm 0.0865$ | $0.8081 \pm 0.0604$ |
| **95% Confidence Interval** | $[0.5886, 0.7269]$ | $[0.6909, 0.8921]$ | $[0.7536, 0.9180]$ | $[0.7233, 0.9221]$ | $[0.7417, 0.9233]$ | $[0.7447, 0.8715]$ |
| **Balanced Accuracy** | $69.55\%$ | $73.64\%$ | $78.79\%$ | $77.42\%$ | $78.59\%$ | **$82.22\%$** |
| **RESTLESS Recall** | $22.55\%$ | $39.41\%$ | $51.35\%$ | $47.64\%$ | $50.28\%$ | **$58.98\%$** |
| **ACTIVE Recall** | $86.28\%$ | $81.62\%$ | $85.49\%$ | $84.81\%$ | $85.78\%$ | **$94.07\%$** |
| **Probability Calibration (ECE)** | $0.0402$ | $0.1308$ | $0.1533$ (Poor) | **$0.0125$** (Excellent) | $0.0295$ (Good) | $0.0854$ (Moderate) |
| **Single-Window Latency** | **$2.2\mu\text{s}$** | $346.8\mu\text{s}$ | $26,299.8\mu\text{s}$ ($26.3\text{ ms}$) | **$4,506.9\mu\text{s}$** ($4.5\text{ ms}$) | **$6,695.2\mu\text{s}$** ($6.7\text{ ms}$) | $6,695.2\mu\text{s}$ |
| **Model Binary Size** | **$0.0\text{ KB}$** | $2.4\text{ KB}$ | $165.0\text{ KB}$ | **$11.37\text{ KB}$** | **$12.96\text{ KB}$** | $12.96\text{ KB}$ |
| **Native Android TFLite Support** | Rule-Based | Custom C++/Java | No (Requires 3P library) | **Yes (LiteRT/TFLite)** | **Yes (Multi-Tensor TFLite)** | **Yes (Multi-Tensor TFLite)** |
| **Missing HR Resilience** | Immune | Degrades | Degrades | Immune | **Graceful ($94.55\%$)** | **Graceful** |

---

## 3. In-Depth Trade-Off Analysis

### 3.1 Why Random Forest Was Not Selected for On-Device Deployment
Random Forest achieved the highest raw tabular Macro F1 ($0.8358$ vs $0.8325$). However, deploying Random Forest on mobile devices introduces severe runtime drawbacks:
1. **CPU & Battery Overhead**: Single-window inference requires $26.3\text{ ms}$ of CPU time across 100 trees. In a continuous foreground sleep recording service running once per second for 8 hours (28,800 inferences per night), evaluating 100 trees creates unnecessary battery drain and scheduling pressure. In contrast, the Multimodal DNN executes in **$6.7\text{ ms}$** ($4\times$ faster).
2. **Probability Calibration Breakdown**: Random Forest exhibits poor probability calibration ($\text{ECE} = 0.1533$). Uncalibrated probability spikes cause erratic downstream smart actuator triggers (e.g. flashing the flashlight or vibrating when true certainty is low).
3. **Runtime Dependency**: Scikit-learn models cannot be executed natively via the lightweight TensorFlow Lite C++ runtime on Android without bundling large third-party ONNX or Treelite wrappers.

### 3.2 Why Multimodal Late-Fusion DNN Was Selected
1. **Sub-10ms Inference**: $6.7\text{ ms}$ inference on mobile CPU allows the Android foreground service to sleep for $99.3\%$ of each 1-second interval, conserving battery.
2. **Minimal Footprint**: The compiled `.tflite` flatbuffer is only **$12.96\text{ KB}$**.
3. **Well-Calibrated Softmax Outputs**: Expected Calibration Error ($\text{ECE} = 0.0295$) ensures that posterior probabilities reflect empirical event likelihoods.
4. **Orientation Invariance**: Incorporates an in-graph dynamic centering layer (`x - tf.reduce_mean(x, axis=1, keepdims=True)`), neutralizing static Earth gravity regardless of 3D phone resting angle without client preprocessing.
5. **Fault-Tolerant Multi-Tensor Architecture**: When BLE heart rate is absent (`hr_mask = 0.0`), the network maintains $94.55\%$ accuracy, smoothly falling back to accelerometer representations.

---

## 4. Deployed Production Model Specifications

### 4.1 Primary Production Model: `multimodal_classifier.tflite`
- **Location**: `app/src/main/assets/multimodal_classifier.tflite`
- **Size**: 12,960 bytes ($12.96\text{ KB}$)
- **SHA-256**: `39e886d49f983a7e3e566eb5a0239634c2af4d8f3976dcfea8dae171af832669`
- **Tensors**:
  - `Input 0`: `serving_default_heart_rate_input:0` (`FLOAT32[1, 2]`)
  - `Input 1`: `serving_default_accelerometer_input:0` (`FLOAT32[1, 20, 3]`)
  - `Output 0`: `StatefulPartitionedCall_1:0` (`FLOAT32[1, 3]`)

### 4.2 Secondary Standby Model: `activity_classifier.tflite`
- **Location**: `app/src/main/assets/activity_classifier.tflite`
- **Size**: 11,368 bytes ($11.37\text{ KB}$)
- **SHA-256**: `62e2d93e8787f0b240562e1cae9bb59fbe1d03ce96f13673f8d83fa599a0937a`
- **Tensors**:
  - `Input 0`: `serving_default_accelerometer_window:0` (`FLOAT32[1, 20, 3]`)
  - `Output 0`: `StatefulPartitionedCall_1:0` (`FLOAT32[1, 3]`)

### 4.3 Production Kotlin Selection Engine (`ModelSelectionEngine.kt`)
The Android application selects inference engines using a deterministic, fault-tolerant state machine:

```mermaid
flowchart TD
    A[Window Complete: 20 Accel Samples @ 20 Hz] --> B{Samples >= 20 & Finite?}
    B -- No --> C[INSUFFICIENT_DATA: ActivityClass.UNCERTAIN]
    B -- Yes --> D{Heart Rate Fresh <= 10s & Valid?}
    D -- Yes --> E[MULTIMODAL_TFLITE: MultimodalClassifier.classify()]
    D -- No (Stale or Missing) --> F[ACCELEROMETER_TFLITE: ActivityClassifier.classify()]
    E --> G{Confidence >= 0.35 & Not UNCERTAIN?}
    G -- Yes --> H[Return ModelSelectionResult: MULTIMODAL_TFLITE]
    G -- No --> F
    F --> I{Confidence >= 0.35 & Not UNCERTAIN?}
    I -- Yes --> J[Return ModelSelectionResult: ACCELEROMETER_TFLITE]
    I -- No --> K[RULE_BASED_FALLBACK: RMS Thresholding]
    K --> L[Return ModelSelectionResult: RULE_BASED_FALLBACK]
```

---

## 5. Verification of Numerical Parity
Numerical parity between Python Keras and Android TFLite is validated by `tests/ml/test_ml_pipelines.py::test_tflite_export_and_numerical_parity`:
- **Maximum Absolute Error**: **$1.18 \times 10^{-7}$** (tolerance threshold $< 1\times 10^{-4}$).
- **Mean Absolute Error**: **$2.45 \times 10^{-8}$**.
- **Golden Vectors File**: `app/src/test/resources/ml_multimodal_golden_vectors.json`.
- **Verdict**: **NUMERICAL_PARITY_VERIFIED**.
