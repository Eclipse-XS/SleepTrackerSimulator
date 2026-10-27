# SLEEPSENSE 2.0 — INDEPENDENT ACCEPTANCE AUDIT
## 03. Room Database v7 Migration & Schema Verification

**Audit Date:** 2026-10-27  
**Database Name:** `sleep_tracker.db`  
**Current Target Version:** **7** (Room 2.8.5)

---

### 1. Schema Evolution & Relational Hierarchy

The database evolution preserves 100% of the historical laboratory tables while introducing the professional SleepSense session model:

```
+-----------------------------------------------------------------------------------+
| Historical Lab Tables (Preserved for compatibility)                               |
| - sensor_records (v1)                                                             |
| - actuator_events (v2)                                                            |
| - cloud_outbox (v3)                                                               |
| - smart_rules & smart_rule_executions (v4)                                        |
| - cloud_active_session (v5)                                                       |
+-----------------------------------------------------------------------------------+
                                          |
                                          | Migration 6->7 (Additive Evolution)
                                          v
+-----------------------------------------------------------------------------------+
| SleepSense 2.0 Product Tables (Version 7)                                         |
| - recording_sessions                                                              |
|     PRIMARY KEY (sessionId)                                                       |
|                                                                                   |
| - sensor_samples                                                                  |
|     FOREIGN KEY (sessionId) REFERENCES recording_sessions(sessionId) ON DELETE CASCADE
|                                                                                   |
| - heart_rate_samples                                                              |
|     FOREIGN KEY (sessionId) REFERENCES recording_sessions(sessionId) ON DELETE CASCADE
|                                                                                   |
| - feature_windows                                                                 |
|     FOREIGN KEY (sessionId) REFERENCES recording_sessions(sessionId) ON DELETE CASCADE
|                                                                                   |
| - predictions                                                                     |
|     FOREIGN KEY (sessionId) REFERENCES recording_sessions(sessionId) ON DELETE CASCADE
|                                                                                   |
| - session_summaries                                                               |
|     FOREIGN KEY (sessionId) REFERENCES recording_sessions(sessionId) ON DELETE CASCADE
|                                                                                   |
| - personal_baselines                                                              |
|     PRIMARY KEY (baselineId)                                                      |
|                                                                                   |
| - devices                                                                         |
|     PRIMARY KEY (deviceId)                                                        |
+-----------------------------------------------------------------------------------+
```

---

### 2. Migration Matrix Verification

| Migration Step | Source Version | Target Version | Strategy | Automated Test Status |
|---|---|---|---|---|
| `MIGRATION_1_2` | 1 | 2 | `CREATE TABLE actuator_events` + indexes | PASS |
| `MIGRATION_2_3` | 2 | 3 | `CREATE TABLE cloud_outbox` + indexes | PASS |
| `MIGRATION_3_4` | 3 | 4 | `CREATE TABLE smart_rules` + executions | PASS |
| `MIGRATION_4_5` | 4 | 5 | Add correlation columns to rules | PASS |
| `MIGRATION_5_6` | 5 | 6 | `CREATE TABLE cloud_active_session` | PASS |
| `MIGRATION_6_7` | 6 | 7 | `CREATE TABLE recording_sessions`, `sensor_samples`, `heart_rate_samples`, `feature_windows`, `predictions`, `session_summaries`, `personal_baselines`, `devices` | PASS |

---

### 3. Verification Test Evidence

1. **Incremental & Sequential Migrations (`SleepTrackerMigrationV6ToV7Test`):**
   - Verified that all published schemas from version 1 to 6 migrate cleanly to version 7 without data loss (`everyPublishedSchemaMigratesTo7AndReopens`).
   - Verified that pre-existing rows in legacy `sensor_records` and `smart_rule_executions` remain readable after upgrade to v7 (`migration6To7CreatesNewTablesAndPreservesLegacyRows`).

2. **Foreign Key CASCADE Enforcement:**
   - Deleting a parent `RecordingSession` automatically purges related `sensor_samples`, `heart_rate_samples`, `feature_windows`, and `predictions` via SQLite cascade constraints, preventing orphaned records.

3. **Data Retention Policy:**
   - `DataRetentionPolicy` preserves raw high-frequency sensor samples for **14 days**.
   - Aggregated `feature_windows`, `predictions`, and `session_summaries` are retained indefinitely for personal longitudinal baseline analytics.

**Verdict:** `IMPLEMENTED` & `COMPILED` & `EXECUTED` (Full additive non-destructive migration verified on device).
