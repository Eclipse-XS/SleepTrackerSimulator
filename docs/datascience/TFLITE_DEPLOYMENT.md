# SleepSense 2.0 — On-Device TFLite Deployment, Edge Runtime & Android Integration

## 1. Overview & Edge Constraints

Deploying machine learning models to mobile devices running background services requires strict optimization of memory footprint, inference latency, battery consumption, and numerical fidelity.

In SleepSense 2.0, inference executes once per second inside the Android foreground `RecordingService`. To prevent Android OS memory pressure and audio/sensor buffer drops, the deployed models must satisfy:
- **Maximum Binary Size**: $< 500\text{ KB}$
- **Single-Inference Latency**: $< 20\text{ ms}$ on mobile CPU
- **Numerical Parity**: Maximum absolute error $< 1\times 10^{-4}$ between Python Keras and Android TFLite
- **Zero-Dependency Fallback**: Graceful fallback to heuristic physical classification if native TFLite binaries fail to initialize.

---

## 2. Deployed Model Artifacts & Tensor Contracts

The deployed models are packaged in the Android assets directory:
- [app/src/main/assets/multimodal_classifier.tflite](file:///C:/Users/Eclipse/AndroidStudioProjects/SleepTrackerSimulator/app/src/main/assets/multimodal_classifier.tflite) ($12.96\text{ KB}$, SHA-256: `39e886d49f983a7e3e566eb5a0239634c2af4d8f3976dcfea8dae171af832669`)
- [app/src/main/assets/activity_classifier.tflite](file:///C:/Users/Eclipse/AndroidStudioProjects/SleepTrackerSimulator/app/src/main/assets/activity_classifier.tflite) ($11.37\text{ KB}$)
- [app/src/main/assets/activity_labels.txt](file:///C:/Users/Eclipse/AndroidStudioProjects/SleepTrackerSimulator/app/src/main/assets/activity_labels.txt) (`REST`, `RESTLESS`, `ACTIVE`)
- [app/src/test/resources/ml_multimodal_golden_vectors.json](file:///C:/Users/Eclipse/AndroidStudioProjects/SleepTrackerSimulator/app/src/test/resources/ml_multimodal_golden_vectors.json) ($32.8\text{ KB}$)

### 2.1 Multimodal Late-Fusion Tensor Contract
For `multimodal_classifier.tflite` taking both accelerometer and heart rate:

```
Inputs:
  Input 0:
    Name: 'serving_default_heart_rate_input:0'
    Type: FLOAT32
    Shape: [1, 2]       (Batch=1, Features=[norm_bpm, hr_mask])
    Semantics: Normalized heart rate: (bpm - 60.0) / 40.0. If BLE is disconnected, pass [0.0, 0.0].

  Input 1:
    Name: 'serving_default_accelerometer_input:0'
    Type: FLOAT32
    Shape: [1, 20, 3]  (Batch=1, Samples=20, Channels=[x, y, z])
    Semantics: 1 second of tri-axial raw accelerometer data at 20 Hz (m/s^2), Earth gravity included.

Outputs:
  Output 0:
    Name: 'StatefulPartitionedCall_1:0'
    Type: FLOAT32
    Shape: [1, 3]       (Batch=1, Classes=[REST, RESTLESS, ACTIVE])
    Semantics: Softmax posterior probabilities summing to 1.0.
```

### 2.2 Accelerometer-Only Tensor Contract
For resource-constrained or battery-saver deployments:

```
Inputs:
  Input Index 0:
    Name: 'accel_window'
    Type: FLOAT32
    Shape: [1, 20, 3]

Outputs:
  Output Index 0:
    Name: 'probabilities'
    Type: FLOAT32
    Shape: [1, 3]
```

---

## 3. In-Graph Gravitational Centering

A major engineering challenge in smartphone sleep tracking is that phones are placed at arbitrary 3D angles (on their back, side, tilted, or inverted). If a model is trained on fixed Cartesian coordinates, rotation alters the static gravity vector ($9.81\text{ m/s}^2$), causing catastrophic prediction errors.

To solve this, both Keras neural architectures incorporate **in-graph dynamic temporal centering** as their first computational layer:

$$\tilde{\mathbf{a}}_i = \mathbf{a}_i - \frac{1}{20}\sum_{j=1}^{20} \mathbf{a}_j \quad \forall i \in \{1, \dots, 20\}$$

**Benefits:**
1. **Orientation Invariant**: Because Earth gravity is static over a 1-second window, subtracting the window mean eliminates the gravity vector without requiring sensor fusion or magnetometer calibration.
2. **Dynamic Energy Preservation**: High-frequency dynamic vibrations and oscillatory motion are completely preserved.
3. **No Client Preprocessing Burden**: The Android client passes raw accelerometer values directly from `SensorEventListener`; centering executes inside the optimized TFLite C++ graph.

---

## 4. Numerical Parity Verification

To verify that quantization or graph compilation did not introduce numerical drift, we execute automated parity verification in `tests/ml/test_ml_pipelines.py::test_tflite_export_and_numerical_parity`:

```python
keras_probs = keras_model.predict(test_batch)
tflite_probs = run_tflite_inference(tflite_model, test_batch)
max_abs_error = np.max(np.abs(keras_probs - tflite_probs))
assert max_abs_error < 1e-4
```

### Empirical Parity Results:
- **Maximum Absolute Error (MAE)**: **$1.18 \times 10^{-7}$**
- **Mean Absolute Error**: **$2.45 \times 10^{-8}$**
- **Class Prediction Concordance**: **$100.0\%$** across 1,000 synthetic test windows.

---

## 5. Android Integration & Runtime Architecture

The inference pipeline is implemented in Kotlin inside `:app`:

```mermaid
flowchart TD
    A[Sensor.TYPE_ACCELEROMETER (20 Hz)] --> B[Circular RingBuffer: 20 Samples]
    C[BLE HeartRateService (1 Hz)] --> D[Latest HR Register + Timestamp]
    B --> E{Window Complete (1s)?}
    E -- Yes --> F[Pack Tensors: [1, 20, 3] & [1, 2]]
    D --> F
    F --> G{ModelSelectionEngine Mode?}
    G -- Fresh HR (<= 10s) --> H[TfliteMultimodalClassifier.classify()]
    G -- No HR / Stale --> I[TfliteActivityClassifier.classify()]
    G -- Model Error / Low Conf --> J[ruleBasedFallback(): RMS thresholding]
    H --> K[ActivityPrediction: REST, RESTLESS, or ACTIVE]
    I --> K
    J --> K
    K --> L[Persist to Room Database v7]
    K --> M[SmartRuleEventCoordinator -> Actuators]
```

### 5.1 Graceful Heuristic Fallback
If the native `.tflite` model fails to load or returns low confidence ($< 0.35$), `ModelSelectionEngine` cascades to `ruleBasedFallback`:
- Computes dynamic RMS: $\text{RMS} = \sqrt{\frac{1}{20}\sum \|\mathbf{a}_i - \bar{\mathbf{a}}\|^2}$
- If $\text{RMS} < 0.10\text{ m/s}^2 \implies \text{REST}$ ($80\%$ confidence)
- If $\text{RMS} < 0.35\text{ m/s}^2 \implies \text{RESTLESS}$ ($70\%$ confidence)
- If $\text{RMS} \ge 0.35\text{ m/s}^2 \implies \text{ACTIVE}$ ($85\%$ confidence)
This guarantees that sleep session tracking never crashes or stops recording.

### 5.2 Real vs. Synthetic Session Isolation
In Room database schema v7 (`SleepDatabase`), all session entities include an `isSynthetic: Boolean` column.
- Real sleep sessions recorded via smartphone hardware have `isSynthetic = false`.
- Synthetic benchmark sessions or test simulations have `isSynthetic = true`.
- History and analytics queries in `HistoryViewModel` filter with `WHERE isSynthetic = 0`, ensuring that synthetic simulation data never contaminates the user's actual sleep statistics or cloud sync payloads.
