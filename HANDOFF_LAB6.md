# Lab 6 technical handoff

## Result

The project contains a genuine float32 TensorFlow Lite classifier, a 20-sample
ring buffer, contract-matched preprocessing, background inference, reactive UI,
and ML-driven Room and actuator behavior.

## Model contract

- File: `app/src/main/assets/rest_movement_classifier.tflite`
- Architecture: Conv1D 12 filters, global average pooling, Dense 12, Dense 3 softmax
- Parameters: 387
- Size: 4,832 bytes
- SHA-256: `2cdc05994e033fc22fd59e1a869c4d25374e8a9564204d2a3b150bfb2f423c02`
- Input: float32 `[1, 20, 3]`
- Output: float32 `[1, 3]`
- Labels: STILL, MICRO_MOVEMENT, PRONOUNCED_MOVEMENT
- Synthetic held-out-session test accuracy: 0.9306
- Limitation: synthetic evaluation is not evidence of clinical or real-person accuracy

## Verification

- Unit tests: 103 passed
- Instrumented tests: 4 passed on Pixel_10_Pro API 36 emulator
- `assembleDebug`: passed
- `lintDebug`: 0 errors, 12 warnings
- Android runtime: model loaded and performed live inference; STILL 71.7% was
  observed with 0.21 ms displayed latency
- Instrumented 100-run benchmark after 10 warmups: mean 0.166 ms, p95 0.293 ms
  (interpreter call only)
- Automated ADB sensor-value changes were accepted by the emulator console but
  did not propagate as changing Android accelerometer events in this AVD session;
  pronounced-movement UI evidence therefore requires manual Extended Controls
  manipulation

## Key paths

- Kotlin ML module: `app/src/main/java/com/example/sleeptrackersimulator/ml/`
- Unit tests: `app/src/test/java/com/example/sleeptrackersimulator/ml/`
- Instrumented model test: `app/src/androidTest/java/com/example/sleeptrackersimulator/ml/`
- Training pipeline: `ml/train_and_export.py`
- Metrics: `ml/model_metrics.json`
- Evidence: `deliverables/evidence/`
