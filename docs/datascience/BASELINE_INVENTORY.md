# SleepSense 2.0 — Baseline Data Science & Engineering Inventory

**Audit Date**: November 2026
**Auditor**: Principal Data Scientist, Senior ML & Data Engineer
**Git Branch**: `feature/sleepsense-2.0-overhaul`
**Git HEAD**: `00e4310 docs(acceptance): record correlated emulator release evidence`
**Execution Environment**: Windows 11 x64, Python 3.11.9 (`ml\.venv`), Android Gradle Plugin 8.x, Java 17 / Kotlin 2.1.0
**Resource Constraints**: 15.93 GB Total RAM (2.65 GB Free Physical RAM), 46.12 GB Free Storage on C:

---

## 1. Executive Summary & Inventory Scope

This baseline inventory establishes the pre-existing state of the SleepSense 2.0 codebase across its data engineering, machine learning, persistence, and Android mobile integration subsystems. The goal of this overhaul is to elevate SleepSense into an **end-to-end, scientifically honest Data Science and Applied ML portfolio project** without compromising the working Android, BLE GATT, Room persistence, or Firebase cloud sync implementations.

---

## 2. Component-by-Component Baseline Audit

### 2.1 Android Room & SQLite Persistence
- **Operational Database**: Room v7 schema implemented in `app/src/main/java/com/example/sleeptrackersimulator/data/sessions/SessionEntities.kt`.
- **Existing Relational Tables**:
  1. `recording_sessions`: Session metadata, timestamps, `sourceMode` (`REAL`, `SIMULATED`, `DEMO`), `devicePlacement`, `qualityStatus`, coverage.
  2. `sensor_samples`: Raw accelerometer readings (`x`, `y`, `z`, `elapsedRealtimeNanos`, `utcTimestampMillis`, `sourceKind`).
  3. `heart_rate_samples`: BLE HR telemetry (`bpm`, `utcTimestampMillis`, `deviceId`, `sourceKind`).
  4. `feature_windows`: 20-sample sliding windows with physical features (`motionRms`, `variance`, `jerk`, `peakToPeak`, `coverageFraction`).
  5. `predictions`: On-device model inferences (`predictedClass`, `probabilitiesJson`, `confidence`, `inferenceDurationNs`, `modelId`).
  6. `session_summaries`: Aggregate overnight rest statistics (`lowMotionFraction`, `movementEpisodeCount`, `movementRatePerHour`, `averageHeartRateBpm`).
  7. `personal_baselines`: Rolling longitudinal baselines segmented by placement.
  8. `devices`: BLE and hardware sensor registry.
- **Demo Isolation**: Android contains a demo workflow (`DemoDatasetViewModel.kt`, `DemoDatasetGenerator.kt`) that seeds 90 synthetic session summaries with `sourceMode = 'DEMO'` and `sessionId LIKE 'demo-v1-%'`. Foreign keys cascade safely, and `resetDemoNights()` deletes only synthetic demo records without touching real sessions.
- **Deficiency / Gap Identified**:
  - The Android Room database is designed for mobile device storage, not analytical multi-subject modeling.
  - There is currently **no standalone, reproducible analytical SQLite research database** (`data/synthetic/sleepsense_synthetic.sqlite`) with tables for virtual subjects, multi-session ground truth intervals, fault events, model comparison logs, and dataset manifests.

---

### 2.2 Sensor Sampling & Provenance Architecture
- **Sensor Type**: `Sensor.TYPE_ACCELEROMETER` sampled via `AndroidAccelerometerDataSource.kt` at 50,000 $\mu$s nominal period ($20\text{ Hz}$).
- **Signal Definition**: Raw accelerometer readings **include Earth's gravity** ($\sim 9.81\text{ m/s}^2$).
- **Dynamic Centering**: The Android inference engine centers windows dynamically (`dx = x - meanX`) before computing RMS and variance. Both TFLite models include an in-graph Lambda layer (`tf.keras.layers.Lambda(lambda x: x - tf.reduce_mean(x, axis=1, keepdims=True))`) to remove gravitational bias regardless of orientation.
- **Heart Rate Stream**: Acquired via BLE GATT (0x180D Heart Rate Service) or `VirtualSleepBand`. Provenance is tracked in `SourceKind` (`PHONE_REAL`, `BLE_REAL`, `BLE_UNVERIFIED`, `SIMULATED`, `REPLAY`, `IMPORTED`).
- **HR Staleness Contract**: Android `ModelSelectionEngine` strictly checks HR freshness ($\le 10\text{ s}$) and physiological plausibility ($30 \le \text{BPM} \le 240$). If stale or absent, it falls back gracefully to accelerometer-only TFLite or rule-based heuristics.

---

### 2.3 Existing Data Engineering Pipeline
- **Modules Present in `pipelines/`**:
  - `ingestion/android_export_reader.py`: Ingests Android JSON and CSV session exports into Polars DataFrames.
  - `validation/schemas.py`: Pandera Polars schemas for raw accelerometer, heart rate, and window features.
  - `transformation/cleaning.py`: Polars cleaning routines (null filtering, finite check, deduplication by timestamp, interval auditing).
  - `transformation/synchronizer.py`: Multimodal time alignment pairing 20-sample acceleration windows with asynchronous HR telemetry.
  - `features/feature_extractor.py`: Physical feature extraction (RMS, variance, jerk, peak-to-peak, per-axis variances).
  - `datasets/dataset_builder.py`: Writes Hive-partitioned Snappy-compressed Parquet datasets with `dataset_metadata.json`.
  - `analytics/duckdb_analytics.py`: DuckDB SQL analytical views over Parquet datasets.
  - `cli/main.py`: Command-line interface with `ingest`, `validate`, `build-dataset`, `analyze`, and basic `generate-synthetic`.
- **Deficiency / Gap Identified**:
  - `pipelines/cli/main.py`'s `generate_synthetic_session` is an overly simplistic generator: it draws independent uniform random states per window ($p < 0.65$), applies instant normal BPM draws without inertia, models no virtual subject heterogeneity, lacks temporal state persistence (no semi-Markov dynamics), and has no device placement attenuation.
  - **CRITICAL CIRCULAR LABELING BUG**: In `feature_extractor.py` (lines 37–46), unlabeled windows are assigned ground truth labels using the exact heuristic thresholds ($<0.10$ RMS, $<0.015$ variance). As a result, synthetic ML models trained on this output simply learned the heuristic rule, giving artificial near-100% accuracy.
  - Ground truth must be generated from a true latent biological state machine and stored in distinct interval tables separate from feature inputs.

---

### 2.4 Existing ML Architecture & TFLite Models
- **TFLite Assets in `app/src/main/assets/`**:
  1. `activity_classifier.tflite` (11.1 KB, single-input `[1, 20, 3]`, output `[1, 3]`).
  2. `multimodal_classifier.tflite` (12.96 KB, dual-input `[1, 20, 3]` and `[1, 2]`, output `[1, 3]`).
  3. `activity_labels.txt` (`REST`, `RESTLESS`, `ACTIVE`).
  4. `ml_golden_vectors.json`: Golden vectors verifying parity between Keras and TFLite.
- **Normalization Contract**:
  - Accelerometer: 20 tri-axial Float32 samples at 20 Hz, normalized in-graph via dynamic mean subtraction.
  - Heart Rate: 2 Float32 values: `[(bpm - 60.0) / 40.0, hr_available_flag]`. If unavailable or stale, `[0.0, 0.0]`.
  - Classes: Softmax over `[0: REST, 1: RESTLESS, 2: ACTIVE]`.
- **Existing Python Training Scripts**:
  - `ml/training/train_accel_model.py`: Trains Dense baseline and 1D CNN with MLflow logging.
  - `ml/training/train_multimodal_model.py`: Trains late-fusion neural network.
  - `ml/evaluate_models.py`: Compares Rule-Based Baseline, Random Forest, Dense TFLite, and 1D CNN.
  - `ml/evaluation_quality.py`: Leakage-safe `subject_wise_split`, `assert_no_recording_leakage`, and `expected_calibration_error`.
  - `ml/export/export_tflite.py`: Verifies numerical parity between Keras and TFLite ($< 10^{-4}$).
- **Deficiency / Gap Identified**:
  - Lack of systematic cross-subject generalization benchmarks.
  - Absence of a rigorous domain shift experiment (training on one virtual population/noise setting and testing on held-out distributions).
  - Ablation study was limited to static synthetic masking rather than physiological lag, sensor noise, placement decoupling, and BLE packet dropouts.

---

### 2.5 Tests & Tooling Status
- **Pytest**: 19 tests in `tests/` and `ml/`, all 19 currently passing ($100\%$).
- **Android Gradle**: `./gradlew.bat testDebugUnitTest` configured and building cleanly.
- **Dependencies**: Full Data Science stack installed in `ml\.venv` (Polars 2.1.0, DuckDB 2.0.0, Pandera 0.35.0, TensorFlow-CPU 2.20.0, Scikit-Learn 1.9.1, MLflow 3.17.2, Matplotlib 3.11.2).

---

## 3. Gap Analysis Matrix

| Requirement Area | Existing Codebase State | Overhaul Plan |
|---|---|---|
| **Synthetic Time-Series Generator** | Basic window-level random generator in CLI with instant jumps. | Build hierarchical simulator (`ml/simulation/`) with virtual subjects, semi-Markov state machine, inertia-based HR dynamics, physical placement attenuation, and BLE failure injection. |
| **Ground Truth Modeling** | Target leakage: heuristic thresholds assign labels to features. | True latent simulation state generates ground-truth interval tables with overlapping noisy observation distributions. |
| **Relational SQL Database** | Android Room DB only; no dedicated research SQL database. | Implement standalone analytical SQLite database (`data/synthetic/sleepsense_synthetic.sqlite`) with strict FKs, indexes, manifests, and reproducible schema. |
| **DuckDB Analytics** | Basic 4 queries over Parquet in `duckdb_analytics.py`. | 15 comprehensive analytical SQL queries across subjects, sessions, noise regimes, placement decoupling, and data quality trends. |
| **Data Quality Engineering** | Polars deduplication and basic interval checks. | Comprehensive quality gate validating timestamp monotonicity, referential integrity, BLE packet dropouts, and logging explicit data quality events. |
| **Exploratory Data Analysis** | Markdown summaries; no automated visual reporting. | Reproducible EDA script generating statistical distributions, cross-correlation, transition matrices, and publication-ready figures. |
| **Machine Learning Benchmarks** | Dense, 1D CNN, and RF on simple synthetic data. | Formal comparison: Majority Class, Rule Baseline, Logistic Regression, Random Forest, 1D CNN, Late-Fusion Multimodal DNN. GroupKFold subject-independent splits. |
| **Multimodal Ablation & Robustness** | Binary HR mask test in `multimodal_ablation_study.json`. | Rigorous 7-scenario ablation: Accel-only, HR-only, Combined, Missing HR, Noisy HR, Stale HR, and Generator Domain Shift. |
| **Domain Shift Evaluation** | None. | Train on Standard Generator configuration, evaluate on Held-Out Robustness configuration with altered placement, noise, and HR inertia. |
| **TFLite Deployment & Parity** | Existing models in assets; numerical parity test passes. | Reproducible candidate training, parity verification ($<10^{-4}$ max error), latency benchmarking, and updated model card. |
| **Android Integration Safety** | Demo import exists for 90 sessions. | Verify and document explicit boundary: synthetic sessions strictly marked `sourceMode = DEMO` and `sourceKind = SIMULATED`, zero pollution of real user history. |
| **Documentation Deliverables** | 10 specialized reports required in `docs/datascience/`. | Author complete suite of evidence-based Markdown reports with executable artifacts and figures. |

---

## 4. Resource Allocation & Execution Strategy

To respect the available ~2.65 GB free RAM and 46 GB storage:
1. **SMALL Profile**: 12 subjects, 3 sessions/subject, 30 min/session ($\sim 1.3\text{M}$ raw accel rows). Memory-bounded chunked generation.
2. **MEDIUM Profile**: 24 subjects, 4 sessions/subject, 45–60 min/session. Processed in streaming batches to Parquet and SQLite.
3. **LONG_SESSION Profile**: 4 subjects, 1 session of 6 hours for endurance and pipeline streaming verification.

All scripts will enforce deterministic seeding, explicit provenance tracking, and zero destructive actions against existing Android project files.
