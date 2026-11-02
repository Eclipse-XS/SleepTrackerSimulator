# SleepSense 2.0 — Final Data Engineering & Machine Learning Baseline Audit

**Date:** 2026-11-02  
**Auditor:** Principal Data Engineer, Principal ML Engineer & Independent Scientific Auditor  
**Repository:** `C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator`  
**Target Branch:** `feature/sleepsense-2.0-overhaul`  
**Local HEAD SHA:** `04009c82d7f92743a6e5a1a61433394079a85090`  
**Remote Upstream:** `origin/feature/sleepsense-2.0-overhaul` (synchronized, clean working tree)

---

## 1. Executive Summary & Audit Baseline

Before applying any remedial engineering, an exhaustive baseline inspection of the repository was performed across Git status, directory structure, Python virtual environments, SQLite schemas, Parquet datasets, TFLite assets, Android build configuration, and test suites.

| Subsystem | Metric / Status | Verification Method |
|---|---|---|
| **Git Working Tree** | Clean (`04009c8`), tracking `origin/feature/sleepsense-2.0-overhaul` | `git status --short --branch` |
| **Python Environment** | Python 3.11.9 (`ml/.venv`), dependencies verified | `python -m pip check` (0 broken requirements) |
| **Python Pytest Suite** | **27 passed, 12 subtests passed, 2 warnings** in 17.69s | `pytest -v` |
| **Android Unit Tests** | **295 passed, 0 failures, 0 errors** in 1m 43s | `gradlew.bat :app:testDebugUnitTest :virtualsleepband:testDebugUnitTest --rerun-tasks` |
| **Android Build** | Build successful, debug signing enabled | Gradle 9.6 / AGP |
| **Linting (Ruff)** | 70 errors in core (`ml`, `tests`, `pipelines`), 94 errors repo-wide | `ruff check` |
| **CI Workflows** | Only `android-ci.yml` present; **No Python CI workflow exists** | Directory scan of `.github/workflows/` |

---

## 2. Inventory of Current Assets and Hashes

### TFLite and Model Assets (`app/src/main/assets/`)
- `activity_classifier.tflite`
  - Size: 11,368 bytes
  - SHA-256: `1CF844F774355E54F820C4860304DC43E42A4015F57C7097CEE9D16227858311`
- `multimodal_classifier.tflite`
  - Size: 12,960 bytes
  - SHA-256: `39E882DCC35AA1B0420AE3D6D59F83631058DC37D13C0FEF80AB1E858E62C158`
- `activity_labels.txt`
  - Size: 21 bytes (`REST`, `RESTLESS`, `ACTIVE`)
  - SHA-256: `5E0D6FF5EC1963BA2004F53A0E57D421B0DA58ECA145ECB0E0C800822F09ECE1`
- `ml_golden_vectors.json`
  - Size: 32,765 bytes
  - SHA-256: `2BB9B67BEDA4536FBE0CBCC11645F769298D5DC95F6107452E570ACD7D4B61DC`

### Existing Data Storage (`data/`)
- `data/synthetic/sleepsense_synthetic.sqlite`: Analytical SQLite store populated with synthetic profiles.
- `data/synthetic/sleepsense_shifted.sqlite`: Domain-shifted evaluation dataset.
- `data/synthetic/parquet/`: Parquet partition directory partitioned by `ground_truth_label`.
- `data/processed/synthetic_dataset/`: DuckDB / tabular dataset partitioned by `session_id`.

---

## 3. Systematic Issue Classification Matrix

Every issue cited in the assignment prompt has been physically audited against the current local code:

| Issue ID | Domain | Description | Status | Evidence / File Reference |
|---|---|---|---|---|
| **DE-001** | Data Eng | Positional feature-window generation (`n_windows = len // window_size`) without event-time bounds | **CONFIRMED** | `ml/simulation/generator.py:285-300` |
| **DE-002** | Data Eng | Inaccurate coverage calculation (hardcoded `coverage_fraction = 1.0`) | **CONFIRMED** | `ml/simulation/generator.py:353` |
| **DE-003** | Data Eng | SQL Gate 6 enforces false perfection (`OR coverage_fraction != 1.0`) | **CONFIRMED** | `sql/quality_checks.sql:58` |
| **DE-004** | Data Eng | Unconditional database recreation & loose path check (`"synthetic" in path`) | **CONFIRMED** | `ml/data/sqlite_storage.py:56-59`, `ml/simulation/generator.py:104` |
| **DE-005** | Data Eng | Non-deterministic dataset manifests (`manifest_id` uses `int(time.time())`) | **CONFIRMED** | `ml/simulation/generator.py:241` |
| **DE-006** | Data Eng | In-memory Parquet accumulation (`parquet_feature_rows.extend`) | **CONFIRMED** | `ml/simulation/generator.py:134,216` |
| **ML-001** | ML Eng | Future HR measurement leakage (`w_end_utc + 1000` & Kotlin `abs(ref - hr)`) | **CONFIRMED** | `ml/simulation/generator.py:326`, `ModelSelectionEngine.kt:110` |
| **ML-002** | ML Eng | Reconstructed raw tensors mismatch (`[: n_windows * 20]` and `s_start = w_idx * 20`) | **CONFIRMED** | `ml/experiments/runner.py:111`, `ml/experiments/scientific_audit.py:104-107` |
| **ML-003** | ML Eng | Deployed models differ from SQL experiments (`generate_multimodal_dataset` toy generator) | **CONFIRMED** | `ml/training/train_multimodal_model.py:24-80`, `ml/export/export_tflite.py` |
| **ML-004** | ML Eng | Domain shift leakage test tests mock helper rather than production path | **CONFIRMED** | `tests/datascience/test_synthetic_simulation_and_sql.py:186-218` |
| **ML-005** | ML Eng | Final test-set repeatedly reused during hyperparameter tuning | **CONFIRMED** | `ml/experiments/runner.py:590-640` |
| **ML-006** | ML Eng | Minority-class performance degradation under class imbalance (RESTLESS/ACTIVE) | **CONFIRMED** | `ml/experiments/runner.py`, `docs/datascience/` |
| **ML-007** | ML Eng | Domain-shift collapse without placement-aware gating or quality abstention | **CONFIRMED** | Macro F1 ~0.40 on shifted nightstand placement |
| **ML-008** | ML Eng | Paired t-tests on correlated CV folds without subject-level clustering | **CONFIRMED** | `ml/experiments/scientific_audit.py` |
| **ML-009** | ML Eng | Undifferentiated latency and battery claims across Python vs TFLite | **CONFIRMED** | `docs/ml/` and `docs/audit/` |
| **CI-001** | DevOps | Missing Python CI workflow (`python-datascience-ci.yml`) | **CONFIRMED** | `.github/workflows/` lacks Python workflow |

---

## 4. Initial Baseline Test Results

### Python Test Suite (`tests/` and `ml/`)
Command executed:
```powershell
.\ml\.venv\Scripts\python.exe -m pytest -v
```
Output:
```text
============================= test session starts =============================
platform win32 -- Python 3.11.9, pytest-9.2.0, pluggy-1.7.0 -- ml\.venv\Scripts\python.exe
cachedir: .pytest_cache
rootdir: SleepTrackerSimulator
configfile: pyproject.toml
testpaths: tests, ml
collected 27 items

tests/data/test_android_export_e2e.py::test_android_csv_export_to_duckdb_e2e_pipeline PASSED
tests/data/test_android_export_e2e.py::test_schema_incompatibility_raises_deterministic_error PASSED
tests/data/test_android_export_e2e.py::test_json_export_reader_compatibility PASSED
tests/data/test_pipeline.py::test_clean_accelerometer_deduplication_and_null_filtering PASSED
tests/data/test_pipeline.py::test_clean_heart_rate_range_filtering PASSED
tests/data/test_pipeline.py::test_sensor_synchronizer_multimodal_alignment PASSED
tests/data/test_pipeline.py::test_feature_extractor_physical_motion_metrics PASSED
tests/data/test_pipeline.py::test_dataset_builder_and_duckdb_analytics PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_generator_deterministic_reproducibility PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_semi_markov_state_timeline_continuity PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_accelerometer_earth_gravity_and_units PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_heart_rate_inertia_and_bounds PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_failure_simulator_ble_dropout_and_logging PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_sqlite_persistence_and_referential_integrity PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_psi_metric_calculation PASSED
tests/datascience/test_synthetic_simulation_and_sql.py::test_domain_shift_evaluation_source_isolation PASSED
tests/ml/test_ml_pipelines.py::test_deployed_tflite_artifact_integrity PASSED
tests/ml/test_ml_pipelines.py::test_tflite_export_and_numerical_parity PASSED
tests/ml/test_ml_pipelines.py::test_multimodal_model_shapes_and_forward_pass PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_calibration_accepts_zero_confidence_boundary PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_calibration_error_is_bounded PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_calibration_rejects_malformed_inputs PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_leakage_rejects_null_recording_ids PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_recording_leakage_is_rejected PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_subject_split_has_no_subject_overlap PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_subject_split_rejects_empty_and_non_vector_input PASSED
ml/test_evaluation_quality.py::EvaluationQualityTest::test_subject_split_rejects_invalid_fraction PASSED

============= 27 passed, 2 warnings, 12 subtests passed in 17.69s =============
```

### Android Unit Test Suite (`:app` and `:virtualsleepband`)
Command executed:
```powershell
.\gradlew.bat :app:testDebugUnitTest :virtualsleepband:testDebugUnitTest --rerun-tasks
```
Output:
```text
BUILD SUCCESSFUL in 1m 43s
56 actionable tasks: 56 executed
Total XML Testsuites: 53
Total Unit Tests: 295
Failures: 0
Errors: 0
```

---

## 5. Remediation Plan Sequence

Following Part XIII Execution Order:
1. **PHASE 1 & 2: Canonical Temporal Model & Feature Window Building**
   - Implement `ml/data/window_builder.py` providing authoritative `build_windows()`.
   - Event-time half-open intervals `[start_ms, end_ms)`.
   - True sampling coverage calculation.
   - Gap detection, non-finite checks, duplicate detection.
   - Causal HR alignment (strictly `<= cutoff`, signed staleness check `0 <= age <= 10000ms`).
   - Fix Kotlin `ModelSelectionEngine.kt` to use signed age `refTimeMs - recentHeartRate.utcTimestampMillis`.
2. **PHASE 3: Canonical Dataset Loader**
   - Create `ml/data/window_dataset.py` with `load_windows_and_tensors()`.
   - Unify `runner.py` and `scientific_audit.py` to use this single loader.
   - Enforce exact window-id matching to raw sample intervals.
3. **PHASE 4: SQL & Parquet Data Quality Gates**
   - Update `sql/schema.sql` (schema v2.1.0 with quality metadata).
   - Update `sql/quality_checks.sql` (Gate 6 checks real coverage bounds `[0, 1]`, sample counts, finite features).
   - Add mutation tests proving each gate catches defective data.
   - Implement streaming chunked Parquet export in `ml/simulation/generator.py`.
4. **PHASE 5: Safe Deterministic Generation**
   - Implement strict path safety in `ml/data/sqlite_storage.py` (`FAIL_IF_EXISTS`, approved directory validation).
   - Separate stable dataset fingerprint from execution timestamp.
5. **PHASE 6 & 7: Regenerate Data & Retrain/Evaluate Models**
   - Regenerate valid datasets using canonical window builder.
   - Run subject-independent split, train balanced models (class weights / focal loss).
   - Ablation studies (multimodal vs accel-only, missing HR, placement).
6. **PHASE 8 & 9: Model Selection, Staging, TFLite Export & Android Parity**
   - Stage candidate in `ml/artifacts/candidates/`.
   - Verify numerical parity on golden vectors.
   - Promote to Android assets and verify Android tests.
7. **PHASE 10: Python CI**
   - Create `.github/workflows/python-datascience-ci.yml`.
8. **PHASE 11 & 12: Documentation & Final Verification**
   - Update README, data science reports, scorecards.
