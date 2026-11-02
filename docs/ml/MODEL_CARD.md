# Model Card: SleepSense Activity & Multimodal Classifiers v2.0

## Model Details
- **Developer**: SleepSense Architecture Team
- **Model Date**: October 2026
- **Model Version**: 2.0.0 (Multimodal TFLite Release Candidate)
- **Model Types**:
  1. **Accelerometer Single-Branch Classifier**: On-device Feedforward DNN (`activity_classifier.tflite`, 11.1 KB, SHA-256: `1cf804d7fe4de9d2bf8be480d47c3dc19b031737e993b417a598f537bfbc7f20`)
  2. **Multimodal Late-Fusion Classifier**: Dual-Branch Feedforward DNN (`multimodal_classifier.tflite`, 12.96 KB, SHA-256: `39e886d49f983a7e3e566eb5a0239634c2af4d8f3976dcfea8dae171af832669`)
- **Framework**: TensorFlow 2.20.0 / TensorFlow Lite / LiteRT
- **Input Specifications**:
  - `accelerometer_input`: Tensor `[1, 20, 3]` (Float32, 20 tri-axial accelerometer samples at 20 Hz, normalized dynamic acceleration)
  - `heart_rate_input`: Tensor `[1, 2]` (Float32, normalized `[(bpm - 60.0) / 40.0, hr_available_flag]`)
- **Output Specification**: Tensor `[1, 3]` (Float32, normalized Softmax probabilities for `REST`, `RESTLESS`, `ACTIVE`)
- **On-Device Latency**:
  - Single-Branch: ~6.1 $\mu$s on modern Android CPU (single-threaded CPU execution benchmark)
  - Multimodal: ~8.4 $\mu$s on modern Android CPU (single-threaded CPU execution benchmark)
- **Numerical Parity**: Max absolute discrepancy between Keras reference model and exported TFLite runtime is $2.98 \times 10^{-7}$ (well below tolerance $1.0 \times 10^{-4}$).

## Neural Network Architecture (Multimodal Classifier)
```
Input 1: Accelerometer [1, 20, 3]        Input 2: Heart Rate [1, 2]
            │                                       │
         Flatten                                Dense (16, ReLU)
            │                                       │
     Dense (64, ReLU)                           Dense (8, ReLU)
            │                                       │
       Dropout (0.2)                                │
            │                                       │
     Dense (32, ReLU)                               │
            └───────────────┬───────────────────────┘
                            ▼
                    Concatenate [1, 40]
                            │
                     Dense (32, ReLU)
                            │
                     Dense (3, Softmax)
                            │
                            ▼
           Output: Probabilities [1, 3]
              (REST, RESTLESS, ACTIVE)
```

## Intended Use
- **Primary Intended Uses**: Real-time on-device estimation of gross nocturnal movement intensity and restlessness state using smartphone sensors combined with optional wearable heart rate telemetry.
- **Intended User Population**: General mobile users tracking overnight movement patterns, rest quality, and behavioral trends.
- **Out-of-Scope Uses**:
  - Clinical diagnosis of sleep apnea, restless leg syndrome, narcolepsy, or parasomnias.
  - Medical polysomnography (PSG) sleep staging (e.g. distinguishing N1, N2, N3 deep sleep vs REM sleep).

## On-Device Model Selection & Fallback Engine
The Android client implements a deterministic selection engine (`ModelSelectionEngine`):
1. **Multimodal Mode (`MULTIMODAL_TFLITE`)**:
   - Requires active heart rate telemetry fresher than 10 seconds.
   - Requires heart rate within physiologically plausible human range ($30 \le \text{BPM} \le 240$).
   - Flags telemetry provenance (`SIMULATED_HEART_RATE`) when using `VirtualSleepBand`.
2. **Accelerometer-Only Mode (`ACCELEROMETER_TFLITE`)**:
   - Automatically activates if heart rate telemetry is stale ($>10$s), disconnected, or out of range.
3. **Physical Heuristic Baseline (`RULE_BASED_FALLBACK`)**:
   - Evaluates dynamic acceleration RMS ($<0.10$ m/s$^2$) and variance ($<0.015$).
   - Activates automatically if TFLite runtime encounters low confidence or unrecoverable exceptions.
4. **Insufficient Data (`INSUFFICIENT_DATA`)**:
   - Assigned when input window has $<20$ samples or contains NaN/Inf values.

## Factors, Biases & Physical Limitations
- **Phone Placement Decoupling**: Smartphone placed on a rigid nightstand is mechanically decoupled from mattress vibrations. In nightstand mode, accurate rest characterization requires paired wearable heart rate telemetry.
- **Synthetic Training Domain**: The baseline model was trained and validated on synthetic physical movement vectors; all data provenance is tracked explicitly in `DataProvenance.SourceKind`.
- **Clinical Validation Status**: `REAL_WORLD_VALIDATION_PENDING`. Metrics reflect synthetic biomechanical simulations; clinical validation against hospital polysomnography has not been completed.
- **Data Privacy**: 100% of ML inference executes locally on-device. No raw sensor streams are sent to remote servers without explicit user-initiated export.

## Performance Metrics (Synthetic Holdout Set, $N = 900$ Windows)
- **Accuracy**: 0.9844
- **Macro F1 Score**: 0.9844
- **Macro Precision**: 0.9848
- **Macro Recall**: 0.9844
- **Holdout Test Set Disconnection Degradation**: Accuracy drops from 97.50% to 97.08% when HR is masked out (zero input), confirming high resilience to wearable battery depletion or BLE connection loss.
