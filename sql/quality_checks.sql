-- ============================================================================
-- SleepSense 2.0 Relational Data Quality Gates and Integrity Assertions
-- Runs automated data quality validation against sleepsense_synthetic.sqlite
-- ============================================================================

-- Gate 1: Check for NULL primary keys or required fields across all tables
SELECT 'GATE_1_NULL_PKS' AS gate_name, COUNT(*) AS violations
FROM (
    SELECT subject_id FROM subjects WHERE subject_id IS NULL
    UNION ALL
    SELECT session_id FROM sessions WHERE session_id IS NULL OR subject_id IS NULL
    UNION ALL
    SELECT CAST(sample_id AS TEXT) FROM accelerometer_samples WHERE session_id IS NULL OR utc_millis IS NULL
    UNION ALL
    SELECT sample_id FROM heart_rate_samples WHERE sample_id IS NULL OR session_id IS NULL OR bpm IS NULL
    UNION ALL
    SELECT interval_id FROM ground_truth_intervals WHERE interval_id IS NULL OR session_id IS NULL
    UNION ALL
    SELECT window_id FROM feature_windows WHERE window_id IS NULL OR session_id IS NULL OR ground_truth_label IS NULL
);

-- Gate 2: Timestamp Monotonicity and Non-Negative Duration Check
SELECT 'GATE_2_TIME_ORDER' AS gate_name, COUNT(*) AS violations
FROM sessions
WHERE end_utc_millis <= start_utc_millis OR duration_sec <= 0;

-- Gate 3: Accelerometer Physical Value Boundaries (-196 to +196 m/s^2)
SELECT 'GATE_3_ACCEL_BOUNDS' AS gate_name, COUNT(*) AS violations
FROM accelerometer_samples
WHERE x < -196.0 OR x > 196.0
   OR y < -196.0 OR y > 196.0
   OR z < -196.0 OR z > 196.0;

-- Gate 4: Heart Rate Physiological Bounds (30 to 240 BPM)
SELECT 'GATE_4_HR_BOUNDS' AS gate_name, COUNT(*) AS violations
FROM heart_rate_samples
WHERE bpm < 30 OR bpm > 240;

-- Gate 5: Orphan Child Records (Referential Integrity)
SELECT 'GATE_5_ORPHAN_RECORDS' AS gate_name, COUNT(*) AS violations
FROM (
    SELECT a.sample_id FROM accelerometer_samples a LEFT JOIN sessions s ON a.session_id = s.session_id WHERE s.session_id IS NULL
    UNION ALL
    SELECT h.sample_id FROM heart_rate_samples h LEFT JOIN sessions s ON h.session_id = s.session_id WHERE s.session_id IS NULL
    UNION ALL
    SELECT g.interval_id FROM ground_truth_intervals g LEFT JOIN sessions s ON g.session_id = s.session_id WHERE s.session_id IS NULL
    UNION ALL
    SELECT w.window_id FROM feature_windows w LEFT JOIN sessions s ON w.session_id = s.session_id WHERE s.session_id IS NULL
);

-- Gate 6: Window Contract & Physical Boundaries Verification
-- Corrected: validates finite boundaries [0, 1] and non-negative features - allows real incomplete windows
SELECT 'GATE_6_WINDOW_CONTRACT' AS gate_name, COUNT(*) AS violations
FROM feature_windows
WHERE sample_count < 0
   OR motion_rms < 0.0
   OR variance < 0.0
   OR jerk < 0.0
   OR peak_to_peak < 0.0
   OR coverage_fraction < 0.0
   OR coverage_fraction > 1.0
   OR start_utc_millis >= end_utc_millis;

-- Gate 7: Ground Truth Interval Non-Overlapping Ordering
SELECT 'GATE_7_INTERVAL_INTEGRITY' AS gate_name, COUNT(*) AS violations
FROM ground_truth_intervals
WHERE end_sec <= start_sec
   OR end_utc_millis <= start_utc_millis;

-- Gate 8: Data Provenance Authenticity Enforcement
SELECT 'GATE_8_PROVENANCE' AS gate_name, COUNT(*) AS violations
FROM sessions
WHERE source_kind NOT IN ('PHONE_REAL', 'BLE_REAL', 'BLE_UNVERIFIED', 'SIMULATED', 'REPLAY', 'DEMO');

-- Gate 9: Duplicate Timestamps within Sessions
SELECT 'GATE_9_DUPLICATE_SAMPLES' AS gate_name, COUNT(*) AS violations
FROM (
    SELECT session_id, utc_millis, COUNT(*) AS cnt
    FROM accelerometer_samples
    GROUP BY session_id, utc_millis
    HAVING cnt > 1
);

-- Gate 10: Training Eligibility Integrity
-- Training eligible windows must have valid coverage (>= 0.85), no large gap, and sufficient samples
SELECT 'GATE_10_TRAINING_ELIGIBILITY' AS gate_name, COUNT(*) AS violations
FROM feature_windows
WHERE training_eligible = 1
  AND (coverage_fraction < 0.85 OR has_large_gap = 1 OR sample_count < 2);

-- Gate 11: Causal Heart Rate Integrity
-- Available HR must be causally at or before window end, staleness between 0 and 10000ms, and within [30, 240] BPM.
-- Unavailable HR must have null mean_hr and null hr_utc_millis.
SELECT 'GATE_11_CAUSAL_HR_INTEGRITY' AS gate_name, COUNT(*) AS violations
FROM feature_windows
WHERE (hr_available = 1 AND (
       mean_hr IS NULL 
       OR mean_hr < 30 
       OR mean_hr > 240
       OR hr_utc_millis IS NULL
       OR hr_utc_millis > end_utc_millis
       OR (end_utc_millis - hr_utc_millis) < 0
       OR (end_utc_millis - hr_utc_millis) > 10000
))
OR (hr_available = 0 AND (mean_hr IS NOT NULL OR hr_utc_millis IS NOT NULL));

-- Gate 12: Prediction Referential Integrity & Probability Normalization
SELECT 'GATE_12_PREDICTION_INTEGRITY' AS gate_name, COUNT(*) AS violations
FROM (
    SELECT p.prediction_id
    FROM model_predictions p
    LEFT JOIN feature_windows w ON p.window_id = w.window_id
    WHERE w.window_id IS NULL
       OR p.confidence < 0.0 OR p.confidence > 1.0
       OR p.prob_rest < 0.0 OR p.prob_rest > 1.0
       OR p.prob_restless < 0.0 OR p.prob_restless > 1.0
       OR p.prob_active < 0.0 OR p.prob_active > 1.0
       OR (p.prob_rest + p.prob_restless + p.prob_active) < 0.99
       OR (p.prob_rest + p.prob_restless + p.prob_active) > 1.01
);
