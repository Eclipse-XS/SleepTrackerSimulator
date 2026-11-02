-- ============================================================================
-- SleepSense 2.0 Comprehensive Analytical SQL Query Suite
-- Executable against SQLite (sleepsense_synthetic.sqlite) and DuckDB
-- ============================================================================

-- Q1: Sessions and Total Observation Time per Virtual Subject
-- Measures subject balance and session compliance across virtual cohort.
SELECT
    s.subject_id,
    sub.preferred_placement,
    COUNT(s.session_id) AS total_sessions,
    ROUND(SUM(s.duration_sec) / 3600.0, 2) AS total_hours,
    ROUND(AVG(s.duration_sec) / 60.0, 1) AS avg_session_min,
    MIN(s.quality_status) AS overall_quality
FROM sessions s
JOIN subjects sub ON s.subject_id = sub.subject_id
GROUP BY s.subject_id, sub.preferred_placement
ORDER BY s.subject_id;

-- Q2: Session-Duration Distribution Statistics
-- Confirms duration uniformity and detects abnormal session lengths.
SELECT
    COUNT(*) AS total_sessions,
    ROUND(MIN(duration_sec) / 60.0, 2) AS min_duration_min,
    ROUND(AVG(duration_sec) / 60.0, 2) AS avg_duration_min,
    ROUND(MAX(duration_sec) / 60.0, 2) AS max_duration_min,
    ROUND(SUM(duration_sec) / 3600.0, 2) AS cohort_total_hours
FROM sessions;

-- Q3: Ground-Truth Latent State Class Distribution
-- Audits class balance and frequency across all contiguous intervals.
SELECT
    state_label,
    COUNT(*) AS interval_count,
    ROUND(SUM(end_sec - start_sec) / 60.0, 2) AS total_duration_minutes,
    ROUND(100.0 * SUM(end_sec - start_sec) / (SELECT SUM(end_sec - start_sec) FROM ground_truth_intervals), 2) AS duration_percentage
FROM ground_truth_intervals
GROUP BY state_label
ORDER BY duration_percentage DESC;

-- Q4: Biomechanical Movement Intensity per Latent Activity State
-- Demonstrates separation and physical validity of dynamic RMS, Jerk, and Variance.
SELECT
    ground_truth_label,
    COUNT(*) AS window_count,
    ROUND(AVG(motion_rms), 4) AS mean_motion_rms,
    ROUND(AVG(variance), 4) AS mean_variance,
    ROUND(AVG(jerk), 4) AS mean_jerk,
    ROUND(AVG(peak_to_peak), 4) AS mean_peak_to_peak
FROM feature_windows
GROUP BY ground_truth_label
ORDER BY mean_motion_rms ASC;

-- Q5: Heart Rate Distribution by Ground-Truth Activity State
-- Shows physiological coupling, elevation during activity, and autonomic variance.
SELECT
    ground_truth_label,
    COUNT(*) FILTER (WHERE hr_available = 1) AS valid_hr_windows,
    ROUND(AVG(mean_hr), 1) AS mean_bpm,
    ROUND(MIN(mean_hr), 1) AS min_bpm,
    ROUND(MAX(mean_hr), 1) AS max_bpm
FROM feature_windows
GROUP BY ground_truth_label
ORDER BY mean_bpm ASC;

-- Q6: Heart Rate Missingness and BLE Availability Fraction
-- Assesses missing data burden by physical placement and recording session.
SELECT
    s.device_placement,
    COUNT(w.window_id) AS total_windows,
    SUM(w.hr_available) AS available_hr_windows,
    COUNT(w.window_id) - SUM(w.hr_available) AS missing_hr_windows,
    ROUND(100.0 * (1.0 - (1.0 * SUM(w.hr_available) / COUNT(w.window_id))), 2) AS missing_hr_percentage
FROM feature_windows w
JOIN sessions s ON w.session_id = s.session_id
GROUP BY s.device_placement
ORDER BY missing_hr_percentage DESC;

-- Q7: Sensor Impairment and Dropout Rate per Subject
-- Telemetry analysis of sensor and BLE failure events by subject.
SELECT
    s.subject_id,
    COUNT(DISTINCT s.session_id) AS sessions_count,
    COUNT(e.event_id) AS total_fault_events,
    SUM(CASE WHEN e.event_type = 'BLE_DISCONNECT' THEN 1 ELSE 0 END) AS ble_disconnect_events,
    SUM(CASE WHEN e.event_type = 'ACCEL_DROPOUT' THEN 1 ELSE 0 END) AS accel_dropouts,
    SUM(CASE WHEN e.event_type = 'SENSOR_OUTLIER' THEN 1 ELSE 0 END) AS sensor_outliers
FROM sessions s
LEFT JOIN data_quality_events e ON s.session_id = e.session_id
GROUP BY s.subject_id
ORDER BY total_fault_events DESC;

-- Q8: Feature-Window Completeness and Sample Coverage Quality
-- Validates that feature windows strictly satisfy 20-sample contracts.
SELECT
    coverage_fraction,
    sample_count,
    COUNT(*) AS window_count,
    ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM feature_windows), 2) AS coverage_pct
FROM feature_windows
GROUP BY coverage_fraction, sample_count;

-- Q9: Per-Subject Physiological and Behavioral Heterogeneity
-- Compares individual baseline heart rate, restlessness frequency, and movement.
SELECT
    s.subject_id,
    ROUND(AVG(w.motion_rms), 4) AS subject_avg_rms,
    ROUND(AVG(w.mean_hr) FILTER (WHERE w.hr_available = 1), 1) AS subject_avg_bpm,
    ROUND(100.0 * SUM(CASE WHEN w.ground_truth_label = 'REST' THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_rest,
    ROUND(100.0 * SUM(CASE WHEN w.ground_truth_label = 'RESTLESS' THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_restless,
    ROUND(100.0 * SUM(CASE WHEN w.ground_truth_label = 'ACTIVE' THEN 1 ELSE 0 END) / COUNT(*), 1) AS pct_active
FROM sessions s
JOIN feature_windows w ON s.session_id = w.session_id
GROUP BY s.subject_id
ORDER BY s.subject_id;

-- Q10: Model Prediction Class Distribution vs Ground Truth
-- Compares class frequencies predicted by candidate models against ground truth.
SELECT
    p.model_name,
    p.predicted_class,
    COUNT(*) AS prediction_count,
    ROUND(AVG(p.confidence), 4) AS mean_confidence,
    ROUND(AVG(p.inference_duration_us), 2) AS mean_latency_us
FROM model_predictions p
GROUP BY p.model_name, p.predicted_class
ORDER BY p.model_name, p.predicted_class;

-- Q11: Misclassification Distribution by Physical Device Placement
-- Evaluates whether nightstand decoupling impairs prediction accuracy.
SELECT
    s.device_placement,
    p.model_name,
    COUNT(*) AS total_evaluated_windows,
    SUM(CASE WHEN p.predicted_class = w.ground_truth_label THEN 1 ELSE 0 END) AS correct_predictions,
    ROUND(100.0 * SUM(CASE WHEN p.predicted_class = w.ground_truth_label THEN 1 ELSE 0 END) / COUNT(*), 2) AS placement_accuracy_pct
FROM model_predictions p
JOIN feature_windows w ON p.window_id = w.window_id
JOIN sessions s ON p.session_id = s.session_id
GROUP BY s.device_placement, p.model_name
ORDER BY s.device_placement, p.model_name;

-- Q12: Model Performance across High-Noise vs Clean Sensor Sessions
-- Checks model degradation in degraded sessions with sensor outliers or noise.
SELECT
    s.quality_status,
    p.model_name,
    COUNT(*) AS evaluated_windows,
    ROUND(100.0 * SUM(CASE WHEN p.predicted_class = w.ground_truth_label THEN 1 ELSE 0 END) / COUNT(*), 2) AS accuracy_pct
FROM model_predictions p
JOIN feature_windows w ON p.window_id = w.window_id
JOIN sessions s ON p.session_id = s.session_id
GROUP BY s.quality_status, p.model_name
ORDER BY s.quality_status, p.model_name;

-- Q13: Multimodal Prediction Resilience Under BLE Missingness
-- Evaluates performance when HR is available vs when BLE connection drops.
SELECT
    w.hr_available,
    p.model_name,
    COUNT(*) AS evaluated_windows,
    ROUND(100.0 * SUM(CASE WHEN p.predicted_class = w.ground_truth_label THEN 1 ELSE 0 END) / COUNT(*), 2) AS accuracy_pct,
    ROUND(AVG(p.confidence), 4) AS avg_confidence
FROM model_predictions p
JOIN feature_windows w ON p.window_id = w.window_id
GROUP BY w.hr_available, p.model_name
ORDER BY p.model_name, w.hr_available DESC;

-- Q14: Comparative Benchmark Metrics by Model Architecture
-- Synthesizes overall accuracy, average confidence, and latency across models.
SELECT
    p.model_name,
    COUNT(*) AS total_predictions,
    ROUND(100.0 * SUM(CASE WHEN p.predicted_class = w.ground_truth_label THEN 1 ELSE 0 END) / COUNT(*), 2) AS overall_accuracy_pct,
    ROUND(AVG(p.confidence), 4) AS mean_confidence,
    ROUND(AVG(p.inference_duration_us), 2) AS mean_latency_us
FROM model_predictions p
JOIN feature_windows w ON p.window_id = w.window_id
GROUP BY p.model_name
ORDER BY overall_accuracy_pct DESC;

-- Q15: Longitudinal Data Quality and Generation Manifests
-- Audit trail tracking generation parameters, row counts, and schema versions.
SELECT
    manifest_id,
    dataset_version,
    dataset_profile,
    subject_count,
    session_count,
    total_duration_hours,
    accel_sample_count,
    hr_sample_count,
    window_count,
    quality_event_count,
    schema_version,
    created_at_utc
FROM dataset_manifests
ORDER BY created_at_utc DESC;
