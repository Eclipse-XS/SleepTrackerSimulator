# 06 — Rule action, durable outbox and Firebase delivery

External actuator calls are **not** part of an atomic SQLite transaction. The code prepares a durable record/outbox draft, invokes the actuator, then finalizes outcomes. Crash recovery reconciles incomplete entries.

```mermaid
sequenceDiagram
  autonumber
  participant Coord as SessionCoordinatorImpl
  participant Auto as BackgroundAutomationCoordinator
  participant Rule as SmartRuleEventCoordinator
  participant DB as Room v8
  participant Act as Actuator or BLE
  participant Work as CloudOutboxWorker
  participant FB as Firebase RTDB
  Coord->>Auto: Valid sensor sample or model prediction
  Auto->>Auto: Validate predicate, consecutive matches, cooldown
  opt Rule triggers
    Auto->>Rule: Prepare execution
    Rule->>DB: Transaction insert execution + outbox draft (not ready)
    DB-->>Rule: Durable preparation
    Auto->>Act: Execute requested action
    Act-->>Auto: Result or failure
    Auto->>Rule: Finalize actual outcome
    Rule->>DB: Transaction update execution + ready outbox
    Auto->>Work: Schedule unique background work
    Work->>DB: Read ready pending operations
    Work->>FB: Authenticated idempotent request
    alt Remote success
      FB-->>Work: Acknowledged
      Work->>DB: Mark delivered
    else Transient error
      FB-->>Work: Retryable failure
      Work->>Work: Retry with backoff
    end
  end
```

Grounding: `smart/SmartRuleEventCoordinator.kt`, `smart/BackgroundAutomationCoordinator.kt`, `cloud/CloudRecovery.kt`, `cloud/CloudOutboxPersistence.kt`. Actual remote delivery is conditional on consent and credentials.
