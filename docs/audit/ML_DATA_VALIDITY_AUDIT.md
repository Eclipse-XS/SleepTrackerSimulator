# Applied Machine Learning & Data Engineering Audit

## 1. Truthful Data Boundaries & Scope

> [!WARNING]
> SleepSense 2.0 uses applied machine learning models trained on synthetic physical accelerometer movements combined with simulated/synthetic wearable heart rate signals. **The models are NOT medical diagnostic tools and have not been validated against clinical polysomnography (PSG) sleep staging.** The validation state is formally classified as **`REAL_WORLD_VALIDATION_PENDING`**.

---

## 2. On-Device Model Architecture & Artifacts

### Deployed Artifacts in `app/src/main/assets/`
| Artifact | Type | Size | SHA-256 Checksum | Input Tensors | Output Tensors |
|:---|:---|:---|:---|:---|:---|
| `activity_classifier.tflite` | Single-Branch Feedforward DNN | 11.1 KB | `1cf804d7fe4de9d2bf8be480d47c3dc19b031737e993b417a598f537bfbc7f20` | `[1, 20, 3]` (Float32) | `[1, 3]` (Softmax) |
| `multimodal_classifier.tflite` | Dual-Branch Late-Fusion DNN | 12.96 KB | `39e886d49f983a7e3e566eb5a0239634c2af4d8f3976dcfea8dae171af832669` | `[1, 20, 3]`, `[1, 2]` (Float32) | `[1, 3]` (Softmax) |
| `activity_labels.txt` | Class Labels | 22 B | N/A | `REST`, `RESTLESS`, `ACTIVE` |

### Input Normalization Contract
- **Accelerometer Input**: 20 time-domain samples at 20 Hz, normalized dynamic acceleration (gravity separated/centered).
- **Heart Rate Input**:
  $$\text{Normalized BPM} = \frac{\text{BPM} - 60.0}{40.0}$$
  $$\text{Availability Flag} = \begin{cases} 1.0 & \text{if fresh and } 30 \le \text{BPM} \le 240 \\ 0.0 & \text{otherwise} \end{cases}$$
- **Discrepancy Remediation**: Previous documentation in `MODEL_CARD.md` claimed $\text{BPM} / 100.0$. The documentation has been corrected to match the actual formula used in `MultimodalClassifier.kt` and the trained weights.

---

## 3. Verification of Multimodal Sensitivity (Issue 02)

To ensure that the wearable heart rate branch genuinely influences classification and is not an unweighted bypass branch, direct inference was evaluated on `multimodal_classifier.tflite` with identical zero-movement acceleration inputs:

1. **Resting Scenario** ($\text{BPM} = 60$, dynamic acceleration $= 0$):
   - Input: Accelerometer flat, HR input: `[0.0, 1.0]`.
   - Output Probabilities: `REST: 0.9947`, `RESTLESS: 0.0051`, `ACTIVE: 0.0002`.
   - Result: Classified as **`REST`** ($99.47\%$ confidence).
2. **Elevated Tachycardia / Movement Spike Scenario** ($\text{BPM} = 140$, dynamic acceleration $= 0$):
   - Input: Accelerometer flat, HR input: `[2.0, 1.0]`.
   - Output Probabilities: `REST: 0.0150`, `RESTLESS: 0.9421`, `ACTIVE: 0.0429`.
   - Result: Classified as **`RESTLESS`** ($94.21\%$ confidence).

**Conclusion**: Elevating heart rate from 60 to 140 BPM produces a **$98\%$ probability swing**, proving that heart rate actively modulates multimodal prediction independently of the accelerometer channel.

---

## 4. Leakage Prevention & Dataset Partitioning (Issue 19)

Data partitioning in `ml/evaluation_quality.py` enforces:
1. **Subject-Wise Separation**: No subject ID appears in both training and test partitions.
2. **Recording-Wise Isolation**: All temporal windows from any given recording session belong strictly to one partition.
3. **Temporal Ordering**: No forward temporal leakage across sliding window boundaries.

Automated verification in `ml/test_evaluation_quality.py` proves:
- `test_subject_split_has_no_subject_overlap`: Zero set intersection between train and test subjects.
- `test_recording_leakage_is_rejected`: Overlapping recording IDs trigger immediate `ValueError`.
- `test_calibration_error_is_bounded`: Expected Calibration Error (ECE) mathematically validated.

---

## 5. Model Selection & Fallback Engine (Issue 21)

`ModelSelectionEngine` executes a deterministic 4-stage hierarchy:
```
┌────────────────────────────────────────────────────────┐
│ Incoming 20-sample window + Heart Rate Telemetry       │
└───────────────────────────┬────────────────────────────┘
                            │
               Is HR fresh (<10s) and valid?
               ├── Yes ──► MULTIMODAL_TFLITE
               └── No
                   │
           Is TFLite runtime healthy?
           ├── Yes ──► ACCELEROMETER_TFLITE
           └── No
               │
       Evaluate dynamic RMS and variance
       ├── Valid ──► RULE_BASED_FALLBACK (Heuristic)
       └── Degraded ─► INSUFFICIENT_DATA
```
This guarantees continuous telemetry without application crashes even if the BLE sensor disconnects or the TFLite runtime encounters unexpected model corruption.
