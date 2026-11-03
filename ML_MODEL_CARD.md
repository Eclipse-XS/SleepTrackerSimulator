# Model card

## Deployed artifacts

`activity_classifier.tflite` (SHA-256 prefix `1cf844f774355e54`) consumes float32 `[1,20,3]` accelerometer XYZ and emits `[1,3]` for `REST`, `RESTLESS`, `ACTIVE`.

`multimodal_classifier.tflite` (SHA-256 prefix `39e882dcc35aa1b0`) consumes the same accelerometer tensor plus float32 `[1,2]`: `(bpm - 60) / 40` and `hr_available`. The current selection engine invokes this artifact only for fresh valid HR and otherwise invokes the accelerometer artifact. Therefore production does not yet satisfy the long-term single-model requirement.

Both models were trained on deterministic synthetic data. The recorded accelerometer test accuracy is 0.9722 on a synthetic held-out split. This result does not establish real-world movement accuracy, HR benefit, sleep-stage accuracy, medical validity, subject generalization, or probability calibration. Classes are movement states, not REM/N1/N2/N3.

No retraining was performed in this hardening change. There is no suitable synchronized, labeled, subject-separated real accelerometer+HR dataset in the repository. Multimodal validation is `BLOCKED_BY_DATA`. Synthetic HR is permitted only for transport and pipeline tests.

Runtime fallback is explicitly recorded as `modelId=rule-based-fallback`, `modelVersion=heuristics-v1.0`, with `FALLBACK_ENGAGED`; it is not a neural prediction. A future release should either make the multimodal model accept missing HR in production or remove it until real-data evaluation meets a baseline defined before training.

