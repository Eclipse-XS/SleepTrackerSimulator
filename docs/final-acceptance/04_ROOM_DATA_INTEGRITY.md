# Room data integrity — 2026-10-29

Status: `VERIFIED_INTEGRATION`

Database: `sleep_tracker.db`, Room schema version 7. A consistent debug snapshot was taken after stopping the app, including the database, WAL, and SHM files.

Correlated session: `2c0d27de-8bc7-4b3e-9ede-3d842571fad4`

| Table | Rows | Correlation result |
|---|---:|---|
| recording_sessions | 1 | COMPLETED, PHONE_ONLY, MATTRESS |
| sensor_samples | 1326 | Same sessionId, PHONE_REAL |
| feature_windows | 66 | Same sessionId, 20/20 samples, coverage 1.0 |
| predictions | 66 | Same sessionId, model `activity-v2.1` |
| session_summaries | 1 | Persisted and rendered in UI |
| heart_rate_samples | 0 | No physical/cross-device HR source |
| cloud_outbox | 23 | Pending offline operations retained |
| actuator_events | 0 | No rule triggered for static signal |

The verified chain is Sensor event → Room sample → feature window → TFLite prediction → session summary → History.
