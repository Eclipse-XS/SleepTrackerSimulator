-- SleepSense 2.0 Relational Analytical Dataset Schema (SQLite / DuckDB compatible)
-- Enforces strict referential integrity, indexes for analytical querying,
-- and explicit separation between feature windows, ground-truth intervals, and model predictions.

PRAGMA foreign_keys = ON;

-- 1. Virtual Subjects Population
CREATE TABLE IF NOT EXISTS subjects (
    subject_id TEXT PRIMARY KEY,
    baseline_hr REAL NOT NULL,
    hr_response_amplitude REAL NOT NULL,
    hr_tau_rise_sec REAL NOT NULL,
    hr_tau_recovery_sec REAL NOT NULL,
    hr_variability_std REAL NOT NULL,
    movement_scale REAL NOT NULL,
    restlessness_tendency REAL NOT NULL,
    preferred_placement TEXT NOT NULL,
    device_orientation_euler TEXT NOT NULL,
    sensor_noise_std REAL NOT NULL,
    ble_packet_loss_rate REAL NOT NULL,
    typical_duration_min REAL NOT NULL,
    generator_version TEXT NOT NULL,
    created_at_utc_ms INTEGER NOT NULL
);

-- 2. Recording Sessions
CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    subject_id TEXT NOT NULL REFERENCES subjects(subject_id) ON DELETE RESTRICT,
    start_utc_millis INTEGER NOT NULL,
    end_utc_millis INTEGER NOT NULL,
    duration_sec REAL NOT NULL,
    device_placement TEXT NOT NULL,
    sampling_rate_hz INTEGER NOT NULL DEFAULT 20,
    simulation_seed INTEGER NOT NULL,
    source_kind TEXT NOT NULL DEFAULT 'SIMULATED',
    generator_version TEXT NOT NULL,
    quality_status TEXT NOT NULL DEFAULT 'EXCELLENT'
);

CREATE INDEX IF NOT EXISTS idx_sessions_subject ON sessions(subject_id);
CREATE INDEX IF NOT EXISTS idx_sessions_start ON sessions(start_utc_millis);

-- 3. Raw Accelerometer Time-Series Samples
CREATE TABLE IF NOT EXISTS accelerometer_samples (
    sample_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    elapsed_nanos INTEGER NOT NULL,
    utc_millis INTEGER NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    accuracy INTEGER NOT NULL DEFAULT 3,
    source_kind TEXT NOT NULL DEFAULT 'SIMULATED'
);

CREATE INDEX IF NOT EXISTS idx_accel_session_time ON accelerometer_samples(session_id, utc_millis);

-- 4. BLE Heart Rate Telemetry Samples
CREATE TABLE IF NOT EXISTS heart_rate_samples (
    sample_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    bpm INTEGER NOT NULL CHECK(bpm >= 30 AND bpm <= 240),
    utc_millis INTEGER NOT NULL,
    timing_uncertainty_millis INTEGER NOT NULL DEFAULT 50,
    source_kind TEXT NOT NULL DEFAULT 'SIMULATED'
);

CREATE INDEX IF NOT EXISTS idx_hr_session_time ON heart_rate_samples(session_id, utc_millis);

-- 5. Ground Truth Activity State Intervals (Latent Simulation State)
CREATE TABLE IF NOT EXISTS ground_truth_intervals (
    interval_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    start_utc_millis INTEGER NOT NULL,
    end_utc_millis INTEGER NOT NULL,
    start_sec REAL NOT NULL,
    end_sec REAL NOT NULL,
    state_id INTEGER NOT NULL CHECK(state_id IN (0, 1, 2)),
    state_label TEXT NOT NULL CHECK(state_label IN ('REST', 'RESTLESS', 'ACTIVE'))
);

CREATE INDEX IF NOT EXISTS idx_gt_session_time ON ground_truth_intervals(session_id, start_utc_millis, end_utc_millis);

-- 6. Synchronized Multimodal Feature Windows
CREATE TABLE IF NOT EXISTS feature_windows (
    window_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    start_utc_millis INTEGER NOT NULL,
    end_utc_millis INTEGER NOT NULL,
    sample_count INTEGER NOT NULL DEFAULT 20,
    coverage_fraction REAL NOT NULL DEFAULT 1.0,
    motion_rms REAL NOT NULL,
    variance REAL NOT NULL,
    jerk REAL NOT NULL,
    peak_to_peak REAL NOT NULL,
    mean_hr REAL,
    hr_available INTEGER NOT NULL CHECK(hr_available IN (0, 1)),
    ground_truth_label TEXT NOT NULL CHECK(ground_truth_label IN ('REST', 'RESTLESS', 'ACTIVE')),
    provenance TEXT NOT NULL DEFAULT 'SIMULATED',
    max_gap_ms INTEGER NOT NULL DEFAULT 50,
    has_large_gap INTEGER NOT NULL DEFAULT 0 CHECK(has_large_gap IN (0, 1)),
    was_resampled INTEGER NOT NULL DEFAULT 0 CHECK(was_resampled IN (0, 1)),
    training_eligible INTEGER NOT NULL DEFAULT 1 CHECK(training_eligible IN (0, 1)),
    quality_flags TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_fw_session_label ON feature_windows(session_id, ground_truth_label);
CREATE INDEX IF NOT EXISTS idx_fw_eligible ON feature_windows(training_eligible);

-- 7. Model Inferences & Predictions
CREATE TABLE IF NOT EXISTS model_predictions (
    prediction_id TEXT PRIMARY KEY,
    window_id TEXT NOT NULL REFERENCES feature_windows(window_id) ON DELETE CASCADE,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    model_name TEXT NOT NULL,
    predicted_class TEXT NOT NULL CHECK(predicted_class IN ('REST', 'RESTLESS', 'ACTIVE', 'UNCERTAIN')),
    confidence REAL NOT NULL,
    prob_rest REAL NOT NULL,
    prob_restless REAL NOT NULL,
    prob_active REAL NOT NULL,
    inference_duration_us REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_preds_model_session ON model_predictions(model_name, session_id);

-- 8. Data Quality & Impairment Events
CREATE TABLE IF NOT EXISTS data_quality_events (
    event_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    severity TEXT NOT NULL,
    timestamp_utc_ms INTEGER NOT NULL,
    details TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_dqe_session ON data_quality_events(session_id);

-- 9. Dataset Manifests & Provenance Tracking
CREATE TABLE IF NOT EXISTS dataset_manifests (
    manifest_id TEXT PRIMARY KEY,
    dataset_version TEXT NOT NULL,
    dataset_profile TEXT NOT NULL,
    generator_config_hash TEXT NOT NULL,
    master_seed INTEGER NOT NULL,
    created_at_utc TEXT NOT NULL,
    subject_count INTEGER NOT NULL,
    session_count INTEGER NOT NULL,
    total_duration_hours REAL NOT NULL,
    accel_sample_count INTEGER NOT NULL,
    hr_sample_count INTEGER NOT NULL,
    window_count INTEGER NOT NULL,
    ground_truth_count INTEGER NOT NULL,
    quality_event_count INTEGER NOT NULL,
    schema_version TEXT NOT NULL DEFAULT '2.1.0'
);
