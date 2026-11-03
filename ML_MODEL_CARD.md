# Model card

## Deployed artifacts

`activity_classifier.tflite` (SHA-256 prefix `1cf844f774355e54`) consumes float32 `[1,20,3]` accelerometer XYZ and emits `[1,3]` for `REST`, `RESTLESS`, `ACTIVE`.

`multimodal_classifier.tflite` (SHA-256 prefix `39e882dcc35aa1b0`) consumes the same accelerometer tensor plus float32 `[1,2]`: `(bpm - 60) / 40` and `hr_available`. The current selection engine invokes this artifact only for fresh valid HR and otherwise invokes the accelerometer artifact. Therefore production does not yet satisfy the long-term single-model requirement.

Both models were trained on deterministic synthetic data. Three distinct synthetic evaluation regimes exist in the repository and must not be conflated:
1. **Simple synthetic session-holdout benchmark** (`ml/evaluation/evaluate_tflite_artifact.py`, `ml/training/train_accel_model.py`, session-disjoint split): recorded accelerometer test accuracy is `0.9722` (`0.9844` on the 900-window TFLite parity slice). This is an auxiliary synthetic sanity check, not a subject-independent benchmark.
2. **Subject-separated synthetic benchmark** (`ml/experiments/runner.py`, held-out subjects `SUBJ-011` and `SUBJ-012`, $N = 10,798$ windows): Multimodal Late-Fusion achieves Accuracy `94.54%` and Macro F1 `0.6787` (Accelerometer-only Dense DNN: Accuracy `94.38%`, Macro F1 `0.6716`).
3. **Shifted-domain synthetic benchmark** (nightstand attenuation + elevated noise cohort `SHIFT-001`..`SHIFT-010`, $N = 53,938$ windows): Accuracy drops to `64.74%` and Macro F1 to `0.4037` (the earlier `0.8784` figure is labeled `SUPERSEDED_INVALID_EVALUATION` after fixing a data-source closure bug; see `docs/datascience/DOMAIN_SHIFT_EVALUATION.md`).

None of these synthetic benchmarks establish real-world movement accuracy, HR benefit, sleep-stage accuracy, medical validity, human subject generalization, or probability calibration. Classes are movement states (`REST`, `RESTLESS`, `ACTIVE`), not REM/N1/N2/N3. Cross-language export and feature contracts are specified in `docs/datascience/ANDROID_PYTHON_DATA_CONTRACT.md`.

No retraining was performed in this hardening change. There is no suitable synchronized, labeled, subject-separated real accelerometer+HR dataset in the repository. Multimodal validation is `BLOCKED_BY_DATA`. Synthetic HR is permitted only for transport and pipeline tests.

Runtime fallback is explicitly recorded as `modelId=rule-based-fallback`, `modelVersion=heuristics-v1.0`, with `FALLBACK_ENGAGED`; it is not a neural prediction. A future release should either make the multimodal model accept missing HR in production or remove it until real-data evaluation meets a baseline defined before training.

