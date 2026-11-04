# Android ↔ Python Data & ML Contract (`v2.1`)

This document specifies the authoritative cross-language contract between the Android application (`com.example.sleeptrackersimulator`) and the Python data-engineering / machine-learning pipelines (`pipelines/`, `ml/`). Every invariant below is enforced by automated tests in `DatasetTimestampContractTest.kt`, `AndroidPythonContractFixtureTest.kt`, `AndroidRuntimeFeatureGoldenTest.kt`, `tests/data/test_android_python_contract.py`, and `tests/data/test_android_runtime_features_parity.py`.

---

## 1. Export Schema Version & Formats

- **Current schema version**: `2.1` (`DATASET_EXPORT_SCHEMA_VERSION = "2.1"` in [DatasetCollector.kt](../../app/src/main/java/com/example/sleeptrackersimulator/dataset/DatasetCollector.kt)).
- **Backward compatibility**: Python ingestion ([android_export_reader.py](../../pipelines/ingestion/android_export_reader.py)) continues to read `v2.0` CSV and JSON exports that omit `rejection_reason`, `source_kind`, or `isValid`, applying the documented fallback validity policy (`fallback_range_policy_v1`).

### 1.1 Field Names and Units

| Concept | Android CSV Column / Header | Android JSON Field | Python Ingestion Column | Unit / Domain |
|---|---|---|---|---|
| Session identifier | `# SessionId:` | `sessionId` | `session_id` | UUID / string |
| Experiment protocol | `# ExperimentType:` | `experimentType` | `experiment_type` | `ExperimentType` enum name |
| Device placement | `# Placement:` | `placement` | `placement` | `DevicePlacement` enum name |
| Session-level activity | `# GroundTruthActivity:` | `groundTruthActivity` | `metadata.ground_truth_activity` | `RESTING`, `READING`, `OUT_OF_BED`, `WALKING` |
| Event UTC timestamp | `timestamp_utc_ms` | `utcTimestampMillis` | `utc_millis` | Milliseconds since Unix epoch (`Int64`) |
| Monotonic event timestamp | `elapsed_realtime_ns` | `elapsedRealtimeNanos` | `elapsed_nanos` | Nanoseconds since boot (`Int64`, raw `SensorEvent.timestamp`) |
| Triaxial acceleration | `accel_x`, `accel_y`, `accel_z` | `x`, `y`, `z` | `x`, `y`, `z` | $\text{m/s}^2$ (`Float32`, finite in $[-50, 50]$ for Developer Lab exports; schema bound $[-196, 196]$) |
| Dynamic acceleration | `dynamic_magnitude` | `dynamicMagnitude` | *(recomputed per window)* | $|\|\mathbf{a}\|_2 - 9.80665|\text{ m/s}^2$ |
| Sample validity | `is_valid` | `isValid` | *(separated into `accepted` / `rejected`)* | `Boolean` |
| Rejection reason | `rejection_reason` | `rejectionReason` | `rejected.rejection_reason` | `NON_FINITE_VALUE`, `OUT_OF_RANGE_EXCESSIVE_G`, etc. |
| Raw activity annotation | `ground_truth_label` | `groundTruthLabel` | `ground_truth_activity` | `RESTING`, `READING`, `OUT_OF_BED`, `WALKING` |
| Runtime model output | `predicted_motion_class` | `predictedMotionClass` | `predicted_motion_class` | `REST`, `RESTLESS`, `ACTIVE` (diagnostic only) |
| Supervised ML target | *(optional `motion_class_label`)* | *(optional `motionClassLabel`)* | `motion_class_label` / `label` | `REST`, `RESTLESS`, `ACTIVE`, or `UNLABELED` |
| Sensor provenance | `source_kind` | `sourceKind` | `source_kind` / `provenance` | Canonical `SourceKind` (Section 4) |
| Heart rate | *(JSON only)* | `heartRateSamples[].bpm` | `bpm` | Beats per minute (`Int32` in $[30, 240]$) |

---

## 2. Monotonic-to-UTC Timestamp Mapping (DE-001)

Android `SensorEvent.timestamp` is reported on the monotonic `elapsedRealtimeNanos` clock, **not** wall-clock `System.currentTimeMillis()`.

1. At `DatasetRecordingController.start(...)`, one session-scoped `ClockAnchor(elapsedRealtimeNanos, utcTimestampMillis)` is captured via an injectable `clockAnchorProvider`.
2. Every sample in that session maps its hardware event timestamp through that single anchor:
   $$\text{utcTimestampMillis} = \text{anchor.utcTimestampMillis} + \left\lfloor\frac{\text{sample.timestampNanos} - \text{anchor.elapsedRealtimeNanos}}{10^6}\right\rfloor$$
3. Original `elapsedRealtimeNanos` is preserved verbatim and never replaced with processing time.
4. Consequently, for samples spaced by $\Delta t = 50{,}000{,}000\text{ ns}$, exported `elapsed_realtime_ns` and `timestamp_utc_ms` both advance by exactly $50\text{ ms}$ regardless of CPU scheduling or batch delivery latency.

---

## 3. Activity Annotations vs. Supervised Motion Classes (DE-002, ML-001)

SleepSense distinguishes three separate label concepts that **must never be conflated**:

1. **`ground_truth_activity`** (`GroundTruthActivity`): experimental protocol annotation recorded by the user (`RESTING`, `READING`, `OUT_OF_BED`, `WALKING`). Preserved verbatim across CSV/JSON export, Python ingestion, window synchronization, and Parquet metadata (`ground_truth_activities`). If a transition occurs mid-window, the window sets `ACTIVITY_TRANSITION` in `quality_flags`.
2. **`predicted_motion_class`**: on-device runtime prediction (`REST`, `RESTLESS`, `ACTIVE`). Retained strictly for diagnostics and model-vs-annotation comparison; **never** used as a training label.
3. **`motion_class_label` / `label`**: supervised ML target in `{REST, RESTLESS, ACTIVE}`.
   - On the default import path, unmapped `ground_truth_activity` values produce `motion_class_label = "UNLABELED"`.
   - Neither `FeatureExtractor` nor `window_builder` nor `window_dataset` is permitted to infer a supervised label from RMS/variance thresholds or default missing/unknown labels to `REST`.
   - Mapping from `GroundTruthActivity` to `{REST, RESTLESS, ACTIVE}` requires an explicit, opt-in, versioned `ActivityToMotionMapping(version=..., mapping=...)`.

---

## 4. Provenance Vocabulary (`SourceKind`, DE-003)

Canonical provenance values match the Kotlin `SourceKind` enum:

- `PHONE_REAL`: physical phone accelerometer hardware.
- `BLE_REAL`: physical Bluetooth Low Energy heart-rate peripheral.
- `BLE_UNVERIFIED`: BLE GATT peripheral without verified physiological authenticity.
- `WEBSOCKET_SIMULATED`: virtual peripheral stream from `VirtualSleepBand` over WebSocket.
- `SIMULATED`: synthetic in-process generator.
- `REPLAY`: deterministic replay of a prior recording.
- `IMPORTED`: external file import.

Legacy values `BLE_SIMULATED` and `DEMO` remain readable for historical exports. Provenance is **never coerced** (`WEBSOCKET_SIMULATED` and `BLE_UNVERIFIED` never become `BLE_REAL`; `REPLAY` and `SIMULATED` never enter `REAL_SENSOR_SOURCE_KINDS`). Unknown `source_kind` strings raise an explicit `ValueError` and never default to `PHONE_REAL`.

---

## 5. Ingestion & Validation Order (DE-004)

`AndroidExportReader` and `pipelines/cli/main.py` execute this deterministic 7-step order:

1. Parse raw records and session header metadata without coercing malformed numbers to `0.0`.
2. Preserve original `is_valid` and `rejection_reason` fields (or apply `fallback_range_policy_v1` when `is_valid` is absent).
3. Partition records into `accepted` and `rejected` DataFrames.
4. Audit rejected records by reason (`NON_FINITE_VALUE`, `OUT_OF_RANGE_EXCESSIVE_G`, `MALFORMED_TIMESTAMP`, `NEGATIVE_TIMESTAMP`).
5. Run `DataCleaner.clean_accelerometer_with_audit` (deduplication on `(session_id, elapsed_nanos)` and chronological sorting) on `accepted` records.
6. Validate cleaned model-ready tables against Pandera (`RawAccelerometerSchema`, `HeartRateSchema`).
7. Persist ingestion, cleaning, and windowing audit counts into the Parquet dataset manifest (`lineage`).

---

## 6. Temporal Windowing, Discontinuities & Coverage (DE-006, DE-007)

- **Maximum inter-sample gap**: $250\text{ ms}$ ($250{,}000{,}000\text{ ns}$), matching Android `SlidingWindowSegmenter`.
- **Online vs. offline semantics**:
  - Online Android streaming (`SlidingWindowSegmenter`) clears its buffer whenever $\Delta t > 250\text{ ms}$ and emits a window once 20 contiguous samples accumulate.
  - Offline Python synchronization (`SensorSynchronizer.synchronize_with_diagnostics`) splits each session stream into contiguous fragments at every gap $> 250\text{ ms}$, recording each break in `inter_fragment_gaps_ms`, and steps through $1{,}000\text{ ms}$ half-open event-time bins $[w_{\text{start}}, w_{\text{start}} + 1000)$ anchored to each fragment start.
- **Sub-bin temporal coverage**: each $1{,}000\text{ ms}$ window is divided into $20$ sub-bins of $50\text{ ms}$; $\text{coverage\_fraction} = \text{occupied\_bins} / 20$. Coverage is never hardcoded to `1.0`.
- **Sample counts & controlled resampling**:
  - `raw_sample_count` (`sample_count`) records the true number of valid sensor observations in the window (e.g., `19` when one sample was dropped).
  - `model_tensor_sample_count` is `20` after `construct_window_tensor` linearly interpolates eligible windows (`coverage_fraction >= 0.85`, `not has_large_gap`) onto the 20 Hz grid (`was_resampled = True`).

---

## 7. Strictly Causal Heart-Rate Alignment (DE-005)

For any window, define the **prediction cutoff** as the event timestamp of the last accelerometer sample in that window:
$$t_{\text{cutoff}} = \max_{s \in W} s.\text{utc\_millis}$$
A candidate heart-rate sample $h$ from the **same session** is eligible if and only if:
$$h.\text{utc\_millis} \le t_{\text{cutoff}} \quad \text{and} \quad 0 \le t_{\text{cutoff}} - h.\text{utc\_millis} \le 10{,}000\text{ ms}$$
The **latest** eligible HR sample at or before $t_{\text{cutoff}}$ is selected (never a future measurement and never an average that includes future or stale samples). In particular, an HR sample arriving between $t_{\text{cutoff}}$ (e.g., $+950\text{ ms}$) and the nominal bin end $w_{\text{end}}$ ($+1000\text{ ms}$) is rejected for that window.

---

## 8. Feature Contracts & Golden Parity (DE/ML-008)

Two versioned feature specifications are defined in [feature_extractor.py](../../pipelines/features/feature_extractor.py):

1. **`android_runtime_features_v1`** (default for Android export pipelines): matches Kotlin `SignalProcessingPipeline.FeatureExtractor` on raw observed samples:
   - $m_i = \sqrt{x_i^2 + y_i^2 + z_i^2}$, $\quad d_i = |m_i - 9.80665|$
   - $\text{motion\_rms} = \sqrt{\frac{1}{N}\sum_{i=1}^{N} d_i^2}$
   - $\text{variance} = \frac{1}{N - 1}\sum_{i=1}^{N} (d_i - \bar{d})^2$ for $N > 1$ else $0$
   - $\text{jerk} = \frac{1}{|I|}\sum_{i \in I} \frac{|m_i - m_{i-1}|}{\Delta t_i}$ where $I = \{i : \Delta t_i = (t_i - t_{i-1}) / 10^9 > 10^{-4}\text{ s}\}$
   - $\text{peak\_to\_peak} = \max_i m_i - \min_i m_i$
   - Verified against Kotlin golden vectors (`tests/data/golden/android_runtime_features_v1.json`) within `rtol = 1e-4, atol = 1e-5` (`atol = 1e-4` for jerk).
2. **`centered_axis_synthetic_v1`**: channel-mean-centered vector norm ($x - \bar{x}, y - \bar{y}, z - \bar{z}$) used by the synthetic SQLite benchmark (`ml/data/window_builder.py`).

---

## 9. Training Eligibility, Group Splitting & Atomic Parquet Publication (ML-001, ML-002, DE-008)

- **Training eligibility**: `training_eligible = signal_eligible and (label in {"REST", "RESTLESS", "ACTIVE"})`. Unlabeled windows remain queryable for analytics in Parquet/DuckDB but are excluded from supervised training.
- **Strict SQLite label loader**: `load_dataset_from_sqlite` validates every window label via `encode_label_strict`, raising `InvalidLabelError(session_id, window_id, label)` on any non-supervised label unless `unlabeled_policy="exclude"` is explicitly requested.
- **Disjoint group splits**: `split_by_group` in `ml/training/train_accel_model.py` partitions train, validation, and test sets by `session_id` (or `subject_id` when provided) with zero overlap and raises `ValueError` if fewer than 3 groups exist.
- **Atomic Parquet publication**: `DatasetBuilder.build_parquet_dataset` writes to a sibling `.staging-<token>` directory, reloads and verifies schema, row counts, unique `window_id` values, partition columns, provenance, and class counts, writes `dataset_metadata.json` and `dataset_manifest.json` (`manifest_version = "2.1"`), and atomically swaps into place only when authorized (`replace_output=True` / `--replace-output`).
