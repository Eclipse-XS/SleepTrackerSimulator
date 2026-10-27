# Model Card: SleepSense Activity Classifier v2.0

## Model Details
- **Developer**: SleepSense Architecture Team
- **Model Date**: October 2026
- **Model Version**: 2.0.0
- **Model Type**: Feedforward Deep Neural Network (TFLite)
- **Input Specification**: Tensor `[1, 20, 3]` (Float32, 20 tri-axial accelerometer samples at 20 Hz)
- **Output Specification**: Tensor `[1, 3]` (Float32, normalized Softmax probabilities for `REST`, `RESTLESS`, `ACTIVE`)
- **Model Size**: 11.10 KB
- **Average Inference Time**: 8.2 $\mu$s on Android CPU

## Intended Use
- **Primary Intended Uses**: Real-time on-device estimation of gross nocturnal movement intensity into coarse activity categories.
- **Intended User Population**: General mobile users tracking overnight movement patterns and trends.
- **Out-of-Scope Uses**: Clinical diagnosis of sleep apnea, insomnia, restless leg syndrome, or medical polysomnography sleep stage determination (e.g. NREM1, NREM2, NREM3, REM).

## Factors & Caveats
- **Placement Sensitivity**: Sensor outputs depend on device placement (e.g., mattress corner vs nightstand vs attached wearable). Baseline comparisons must maintain consistent placement.
- **Synthetic Training Domain**: The baseline model was trained and validated on synthetic physical movement vectors; all data provenance is tracked explicitly in `DataProvenance.SourceKind`.
- **Fairness & Privacy**: Inference runs 100% on-device. No raw accelerometer data is transmitted to cloud servers without explicit user consent.

## Performance Metrics (Holdout N=750)
- **Accuracy**: 0.9813
- **Macro F1 Score**: 0.9813
- **Macro Precision**: 0.9816
- **Macro Recall**: 0.9813
