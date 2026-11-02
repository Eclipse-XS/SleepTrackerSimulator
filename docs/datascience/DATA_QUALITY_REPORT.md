# SleepSense 2.0 — Data Quality Engineering, Integrity Gates & Drift Report

## 1. Quality Engineering Philosophy

In physiological signal processing and edge ML systems, silent data corruption, timestamp jitter, unmonitored packet dropouts, and feature drift can degrade model reliability without throwing explicit runtime exceptions.

SleepSense 2.0 incorporates a **defense-in-depth data quality framework**:
1. **Relational Constraints & Foreign Keys**: Enforced at the persistence boundary via SQLite `PRAGMA foreign_keys = ON;`.
2. **Schema & Contract Verification**: Pandera schema checks validating column types, bounded ranges, and nullability.
3. **Automated SQL Quality Gates**: 9 automated integrity checks ([sql/quality_checks.sql](file:///C:/Users/Eclipse/AndroidStudioProjects/SleepTrackerSimulator/sql/quality_checks.sql)) integrated into the CI test suite.
4. **Structured Telemetry Event Logging**: Dedicated `data_quality_events` table recording physical and wireless transmission faults.
5. **Statistical Distribution & Drift Monitoring**: Population Stability Index (PSI) tracking to detect domain shifts.

---

## 2. Automated Relational Data Quality Gates (9 Gates, 0 Violations)

Every generated SQLite database is subjected to 9 automated quality validation gates executed by `tests/datascience/test_synthetic_simulation_and_sql.py::test_sql_quality_assurance_gates`.

All 9 gates executed against `data/synthetic/sleepsense_synthetic.sqlite` passed with **0 violations**:

| Gate # | Integrity Check Name | Invariant Tested | SQL Verification Logic | Violations Detected | Status |
|---|---|---|---|:---:|:---:|
| **Gate 1** | `check_no_null_foreign_keys` | Referential integrity across child tables | `SELECT COUNT(*) FROM sessions WHERE subject_id IS NULL ...` | **0** | **PASS** |
| **Gate 2** | `check_accel_sample_rate_integrity` | Sampling interval consistency (20 Hz nominal) | Inter-sample $\Delta t$ within $[40\text{ ms}, 65\text{ ms}]$ | **0** | **PASS** |
| **Gate 3** | `check_hr_physiologically_bounded` | Plausible human cardiovascular bounds | Heart rate strictly bounded: $40 \le \text{HR} \le 220\text{ bpm}$ | **0** | **PASS** |
| **Gate 4** | `check_ground_truth_interval_ordering` | Interval validity & positive duration | Ground truth intervals satisfy `start_time_ms < end_time_ms` | **0** | **PASS** |
| **Gate 5** | `check_no_negative_motion_rms` | Non-negative physical energy metrics | `motion_rms >= 0 AND variance >= 0 AND peak_to_peak >= 0` | **0** | **PASS** |
| **Gate 6** | `check_model_confidence_sums_to_one` | Softmax probability conservation | $\lvert (P_{\text{rest}} + P_{\text{restless}} + P_{\text{active}}) - 1.0 \rvert \le 0.01$ | **0** | **PASS** |
| **Gate 7** | `check_no_orphaned_predictions` | Model prediction referential integrity | Every prediction references an extant `feature_windows(window_id)` | **0** | **PASS** |
| **Gate 8** | `check_timestamp_monotonicity` | Strict temporal monotonicity within sessions | `timestamp_ms >= LAG(timestamp_ms)` per session | **0** | **PASS** |
| **Gate 9** | `check_valid_class_labels` | Label enumeration consistency | Class labels restricted to `REST`, `RESTLESS`, `ACTIVE` | **0** | **PASS** |

---

## 3. Data Quality Fault Event Telemetry Audit

Wireless BLE peripherals and smartphone operating system schedulers periodically experience dropped connections, thermal throttling, or thread starvation. The simulation engine deterministically models and logs these occurrences in `data_quality_events`:

### 3.1 Standard Benchmark Dataset (`sleepsense_synthetic.sqlite`)
- **Total Sessions**: 36 sessions (18.0 observation hours across 12 virtual subjects)
- **Excellent Sessions**: 21 sessions (100% telemetry completeness)
- **Degraded Sessions**: 15 sessions (contained $\ge 1$ handled fault event)
- **Total Injected Events**: **21 events**
  - **`BLE_DISCONNECT` (9 events)**: Simulated temporary loss of BLE GATT connection ($30\text{s} - 120\text{s}$). During disconnections, the receiver enters missing-data hold mode.
  - **`ACCEL_DROPOUT` (6 events)**: Accelerometer FIFO buffer stall drops 10–40 samples ($0.5\text{s} - 2.0\text{s}$) due to OS background scheduling contention.
  - **`SENSOR_OUTLIER` (6 events)**: 8G saturation glitch representing severe external mechanical impulse.

### 3.2 Missing Heart Rate Analysis
Out of **64,780 feature windows**, exactly **440 windows** (0.68%) experienced total or partial heart-rate packet loss (`missing_hr_fraction > 0`).

**Handling Policy in ML Pipeline:**
- Missing HR values are replaced with subject-specific median imputation or population baseline ($65.0\text{ bpm}$).
- An explicit binary missingness indicator column (`is_hr_missing`) is concatenated to the feature vector.
- The model architecture uses late-fusion gating, ensuring that missing HR input degrades graceful to accelerometer-only performance rather than producing numerical instability or spurious predictions.

---

## 4. Population Stability Index (PSI) & Feature Drift Analysis

To evaluate real-world wearability robustness, we compared the standard benchmark cohort against a challenging domain-shifted cohort (`data/synthetic/sleepsense_shifted.sqlite`, 10 subjects, 30 sessions, 15 hours). The shifted cohort simulates phone placement on a nightstand (86% mechanical decoupling), elevated sensor thermal noise (+300%), and altered autonomic recovery constants.

The Population Stability Index is computed across 10 quantile bins:
$$\text{PSI} = \sum_{b=1}^{10} \left( P_b - Q_b \right) \times \ln\left( \frac{P_b}{Q_b} \right)$$
where $P_b$ is the baseline distribution fraction and $Q_b$ is the shifted distribution fraction.

### 4.1 Empirical PSI Drift Matrix

| Feature | Baseline Mean ($\pm$ Std) | Shifted Mean ($\pm$ Std) | Computed PSI | Alert Status | Root Cause & Operational Impact |
|---|:---:|:---:|:---:|:---:|---|
| **`motion_rms`** | $0.0808 \pm 0.3148$ | $0.0773 \pm 0.1191$ | **10.2419** | **SEVERE_DRIFT** | Nightstand mechanical decoupling attenuates macro-movement peaks while background vibration noise increases. |
| **`variance`** | $0.0370 \pm 2.3198$ | $0.0036 \pm 0.0524$ | **4.9806** | **SEVERE_DRIFT** | Nightstand placement dampens high-amplitude variance spikes by an order of magnitude. |
| **`jerk`** | $1.0037 \pm 5.8411$ | $0.8264 \pm 1.7158$ | **4.6310** | **SEVERE_DRIFT** | First-order acceleration derivatives show severe high-frequency damping on wooden furniture. |
| **`peak_to_peak`** | $0.1243 \pm 0.8048$ | $0.1108 \pm 0.1932$ | **4.5715** | **SEVERE_DRIFT** | Dynamic range compression due to acoustic boundary reflection and distance from the mattress. |
| **`mean_hr`** | $64.30 \pm 8.70$ | $75.70 \pm 7.40$ | **4.1054** | **SEVERE_DRIFT** | Shifted subject population parameterized with higher resting baseline and sluggish vagal recovery. |

### 4.2 Quality Gate Thresholds for Production Monitoring
- $\text{PSI} < 0.10$: Stable distribution (no action required).
- $0.10 \le \text{PSI} < 0.25$: Moderate drift (log telemetry warning; schedule recalibration).
- $\text{PSI} \ge 0.25$: Severe drift (trigger alert; fallback to physical rule heuristic or request user placement confirmation).
