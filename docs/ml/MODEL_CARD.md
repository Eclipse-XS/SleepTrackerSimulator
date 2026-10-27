# Model Card: SleepSense Activity Classifier v2.1

## Model Details
- **Developer**: SleepSense Architecture Team
- **Model Date**: October 2026
- **Model Version**: 2.1.0
- **Model Type**: Feedforward Deep Neural Network (TFLite Float32 FlatBuffer)
- **Input Specification**: Tensor `[1, 20, 3]` (Float32, 20 tri-axial accelerometer samples at 20 Hz)
- **Output Specification**: Tensor `[1, 3]` (Float32, normalized Softmax probabilities for `REST`, `RESTLESS`, `ACTIVE`)
- **Model Size**: 10.75 KB (10,756 bytes)
- **Measured On-Device Inference Latency**: ~0.9 ms – 1.4 ms (Pixel 10 Pro AVD, Android 16, steady-state)
- **Golden Vector Numerical Parity**: 12/12 test vectors match reference with $\Delta P < 1\times 10^{-5}$

## Intended Use
- **Primary Intended Uses**: Real-time on-device estimation of gross nocturnal movement intensity into coarse activity categories.
- **Intended User Population**: General mobile users tracking overnight movement patterns and trends.
- **Out-of-Scope Uses**: Clinical diagnosis of sleep apnea, insomnia, restless leg syndrome, or medical polysomnography sleep stage determination (e.g. NREM1, NREM2, NREM3, REM).

## Factors & Caveats
- **Placement Sensitivity**: Sensor outputs depend on device placement (e.g., mattress corner vs nightstand vs attached wearable). Baseline comparisons must maintain consistent placement.
- **Synthetic Training Domain**: The baseline model was trained and validated on synthetic physical movement vectors; all data provenance is tracked explicitly in `SourceKind`.
- **Fairness & Privacy**: Inference runs 100% on-device. No raw accelerometer data is transmitted to cloud servers without explicit user consent.

## Performance Metrics (Synthetic Holdout N=675)
- **Accuracy**: 0.9956 (99.56%)
- **Macro F1 Score**: 0.995
- **REST F1**: 1.000
- **RESTLESS F1**: 0.991
- **ACTIVE F1**: 0.995
