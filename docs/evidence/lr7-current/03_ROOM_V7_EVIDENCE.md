# Room v7 evidence

`SleepTrackerDatabase.kt` declares schema version 7 and migrations 1→2→3→4→5→6→7. Exported schemas 1–7 exist under `app/schemas/...SleepTrackerDatabase/`.

| Table | Entity/source | Fields | Indices | Foreign keys |
|---|---|---:|---:|---:|
| sensor_records | `SensorRecord.kt` | 4 | 1 | 0 |
| actuator_events | `ActuatorEvent.kt` | 7 | 1 | 0 |
| cloud_outbox | `CloudOutboxPersistence.kt` | 11 | 2 | 0 |
| cloud_active_session | `CloudOutboxPersistence.kt` | 5 | 0 | 0 |
| smart_rules | `SmartRuleModels.kt` | 12 | 1 | 0 |
| smart_rule_executions | `SmartRuleModels.kt` | 17 | 3 | 0 |
| recording_sessions | `SessionEntities.kt` | 14 | 2 | 0 |
| sensor_samples | `SessionEntities.kt` | 10 | 1 | 1 |
| heart_rate_samples | `SessionEntities.kt` | 7 | 1 | 1 |
| feature_windows | `SessionEntities.kt` | 16 | 1 | 1 |
| predictions | `SessionEntities.kt` | 9 | 2 | 2 |
| session_summaries | `SessionEntities.kt` | 12 | 0 | 1 |
| personal_baselines | `SessionEntities.kt` | 10 | 1 | 0 |
| devices | `SessionEntities.kt` | 6 | 0 | 0 |

AndroidJUnitRunner підтвердив 1→latest, 5→6, every published schema→7, 6→7 і збереження legacy rows. Фактична runtime DB на AVD мала 204 KB на момент `dumpsys meminfo`. Це snapshot, не benchmark росту.
