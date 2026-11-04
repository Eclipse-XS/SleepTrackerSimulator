# Model card — deployed SleepSense movement classifiers

## Intended use

On-device classification of coarse movement states: `REST`, `RESTLESS`, `ACTIVE`. **Not** REM/NREM sleep stages, diagnosis, or a validated clinical sleep metric. All published evaluation regimes below use synthetic signals.

## Deployed artifacts

| Asset | SHA-256 | Input | Output |
| --- | --- | --- | --- |
| `activity_classifier.tflite` | `1cf844f774355e54f820c4860304dc43e42a4015f57c7097cee9d16227858311` | float32 `[1,20,3]` XYZ | float32 `[1,3]` |
| `multimodal_classifier.tflite` | `39e882dcc35aa1b0420ae3d6d59f83631058dc37d13c0fef80ab1e858e62c158` | XYZ `[1,20,3]` plus `[1,2]` HR/availability | float32 `[1,3]` |

Source of truth: `ml/artifacts/deployment_manifest.json` and the binary assets in `app/src/main/assets/`. The values above were transcribed from the manifest; this documentation change does not independently rehash binaries.

HR preprocessing: `normalized_bpm = (bpm - 60.0) / 40.0`; availability is an explicit flag. Runtime accepts eligible HR aged 0–10 seconds. The selection engine attempts multimodal inference for valid HR, then accelerometer-only; failed/low-confidence inference may use the rule-based fallback. `UNCERTAIN` is not equated to `REST`.

## Separate synthetic evaluation regimes

| Evaluation regime | Reported result | Interpretation |
| --- | --- | --- |
| Session-holdout sanity check | accuracy `0.9722`; a *different* 900-window TFLite parity slice reports `0.9844` | Synthetic auxiliary evaluation; not subject-independent real-world accuracy |
| Subject-separated synthetic cohort (N=10,798 windows) | multimodal accuracy `94.54%`, macro F1 `0.6787` | Synthetic subject-held-out comparison |
| Shifted synthetic cohort (N=53,938 windows) | accuracy `64.74%`, macro F1 `0.4037` | Noise and nightstand-placement sensitivity; performance degrades markedly |

The earlier `0.8784` shifted score was superseded after an evaluation data-source defect. Do not reuse it. Supporting methods: [domain shift](datascience/DOMAIN_SHIFT_EVALUATION.md), [multimodal ablation](datascience/MULTIMODAL_ABLATION.md), [dataset design](datascience/SYNTHETIC_DATASET_DESIGN.md).

## Failure modes

Unknown phone placement, sensor noise, intermittent heart rate, missing physical movement coupling, class imbalance, synthetic-to-real distribution shift, and uncalibrated confidence scores. No human subject, clinical sleep-stage or wearable-generalization performance is established.
