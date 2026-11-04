# 04 — Room v8 schema and enforced relationships

Canonical schema: `app/schemas/com.example.sleeptrackersimulator.history.SleepTrackerDatabase/8.json`. Lines below represent **actual declared foreign keys**, not conceptual relationships inferred from table names.

```mermaid
erDiagram
  recording_sessions ||--o{ sensor_samples : "CASCADE sessionId"
  recording_sessions ||--o{ heart_rate_samples : "CASCADE sessionId"
  recording_sessions ||--o{ feature_windows : "CASCADE sessionId"
  recording_sessions ||--o{ predictions : "CASCADE sessionId"
  feature_windows ||--o{ predictions : "CASCADE windowId"
  recording_sessions ||--o| session_summaries : "CASCADE sessionId"
  recording_sessions {
    string sessionId PK
    long startUtcMillis
    string status
    string sourceMode
  }
  sensor_samples {
    long sampleId PK
    string sessionId FK
    float x
    float y
    float z
    string sourceKind
  }
  heart_rate_samples {
    long sampleId PK
    string sessionId FK
    int bpm
    string sourceKind
  }
  feature_windows {
    string windowId PK
    string sessionId FK
    float motionRms
    float qualityScore
  }
  predictions {
    string predictionId PK
    string sessionId FK
    string windowId FK
    string predictedClass
    string modelId
  }
  session_summaries {
    string sessionId PK,FK
    float lowMotionFraction
    float dataCoverageFraction
  }
```

Other tables in v8 without **declared database-level foreign keys** in the exported Room schema: `sensor_records`, `actuator_events`, `cloud_outbox`, `cloud_active_session`, `cloud_delivery_intents`, `cloud_session_deletions`, `smart_rules`, `smart_rule_executions`, `personal_baselines`, `devices`. Some have logical references managed in application code; do not misrepresent them as enforced SQL FKs.

Room keeps migration schemas v1–v8 under `app/schemas/`; retain every committed schema JSON used by migration tests.
