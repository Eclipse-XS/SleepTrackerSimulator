# SleepSense 2.0: Synthetic Dataset Reproducibility Report

**Date:** 2026-11-02  
**Manifest ID:** `MANIFEST-SMALL-d059bf819ad7fda6`  
**Dataset Version:** `2.1.0-small`  
**Schema Version:** `2.1.0`  
**Master Random Seed:** `42`  

---

## 1. Overview & Dataset Provenance

SleepSense 2.0 uses a realistic synthetic multi-sensor sleep simulation platform designed to evaluate end-to-end data engineering pipelines, feature window extractors, and on-device machine learning models under controlled conditions without fabricating real-world clinical claims.

All synthetic cohorts are deterministically reproducible from fixed configuration seeds using the CLI command:
```powershell
.\ml\.venv\Scripts\python.exe -m pipelines.cli.main generate --profile SMALL --seed 42
```

---

## 2. Standard Benchmark Cohort Metrics (Profile: SMALL)

### 2.1 Configuration Parameters
- **Profile Name:** `SMALL`
- **Master Random Seed:** `42`
- **Virtual Subjects:** 12 (`SUBJ-001` through `SUBJ-012`)
- **Sessions per Subject:** 3 (Total sessions: 36)
- **Session Duration:** 30.0 minutes (1,800 seconds per session)
- **Total Simulated Sleep Time:** 18.0 hours
- **Nominal Sampling Rate:** 20.0 Hz (Accelerometer), 1.0 Hz (Heart Rate)

### 2.2 Verified Sensor Counts & Totals
| Entity | Total Count | Notes |
| :--- | :--- | :--- |
| **Virtual Subjects** | 12 | Demographically balanced rest/activity profiles |
| **Simulated Sessions** | 36 | 3 sessions per subject, non-overlapping timelines |
| **Raw Accelerometer Samples** | 1,295,810 | Tri-axial acceleration (m/s²), nominal 50ms interval |
| **Raw Heart Rate Samples** | 62,051 | BPM values with physiological autonomic jitter |
| **Total Feature Windows** | 64,800 | 1-second event-time intervals [w_start, w_end) |
| **Training-Eligible Windows** | 56,713 | Met coverage ≥90%, max_gap ≤250ms, valid accel |
| **Ineligible / Damaged Windows**| 8,087 | Retained for quality telemetry; excluded from training |
| **Ground Truth Intervals** | 851 | Semi-Markovian sleep state transitions |
| **Simulated Quality Events** | 21 | Controlled BLE dropouts, sensor disconnections |

### 2.3 Cryptographic Manifest
```json
{
  "manifest_id": "MANIFEST-SMALL-d059bf819ad7fda6",
  "dataset_version": "2.1.0-small",
  "dataset_profile": "SMALL",
  "generator_config_hash": "d059bf819ad7fda6",
  "master_seed": 42,
  "created_at_utc": "2026-11-02T19:46:45.763218+00:00",
  "subject_count": 12,
  "session_count": 36,
  "total_duration_hours": 18.0,
  "accel_sample_count": 1295810,
  "hr_sample_count": 62051,
  "window_count": 64800,
  "ground_truth_count": 851,
  "quality_event_count": 21,
  "training_eligible_count": 56713,
  "schema_version": "2.1.0"
}
```

---

## 3. Shifted Evaluation Cohort (Profile: SHIFTED)

To rigorously test out-of-distribution generalization without data leakage, an independent shifted cohort was generated into an isolated database:
- **Database Path:** `data/synthetic/sleepsense_shifted.sqlite`
- **Master Seed:** `999`
- **Domain Shift Flag:** `True` (high-variance sensor calibration offset, altered sensor noise)
- **Fault Scenario:** `heavy_dropout` (simulating packet drops and hardware interference)
- **Virtual Subjects:** 10 (`SUBJ-001` through `SUBJ-010` in shifted database)
- **Sessions:** 30 (3 per subject, 30 min duration each)
- **Raw Accelerometer Samples:** 1,079,036
- **Raw Heart Rate Samples:** 43,805
- **Total Windows:** 54,000 (Eligible: 48,652, Ineligible: 5,348)

---

## 4. Storage Safety & Determinism Architecture

### 4.1 Canonical Path Validation
The `SqliteStorageManager` enforces an explicit canonical path safety check (`_is_safe_path()`):
- Recreating databases is strictly restricted to targets inside `data/synthetic` or authorized temporary test fixtures (`pytest tmp_path`).
- Any attempt to target paths outside approved locations raises `PermissionError` immediately before any file handles are created or unlinked.

### 4.2 Safe Output Policies
- `FAIL_IF_EXISTS`: Aborts immediately if the destination SQLite database already exists.
- `REPLACE_GENERATED_DATA`: Explicitly approved policy for synthetic regeneration, cleanly unlinking the database file and any `-wal` / `-shm` temporary journal files.

### 4.3 Streaming Bounded-Memory Parquet
In `SyntheticDatasetGenerator.generate_dataset()`:
- Rather than accumulating all 64,800 feature windows in a massive in-memory dictionary or DataFrame, feature windows are streamed session-by-session into a `pyarrow.parquet.ParquetWriter`.
- Peak RAM consumption during 64,800-window generation remains bounded under **100 MB**.
- Partitioned Parquet files are stored under `data/synthetic/parquet/` organized by subject and session.
