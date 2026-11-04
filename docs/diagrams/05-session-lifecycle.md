# 05 — Recording session lifecycle

Model states come from `core/model/RecordingSession.kt`. `RecordingService` returns `START_NOT_STICKY`; restart recovery must not fabricate uninterrupted sensor collection.

```mermaid
stateDiagram-v2
  [*] --> IDLE
  IDLE --> PREPARING: start
  PREPARING --> RECORDING: sources ready
  PREPARING --> FAILED: start failure
  PREPARING --> IDLE: canceled start
  RECORDING --> PAUSED: pause
  PAUSED --> RECORDING: resume
  RECORDING --> FINALIZING: stop
  PAUSED --> FINALIZING: stop
  RECORDING --> INTERRUPTED: process loss
  PAUSED --> INTERRUPTED: process loss
  INTERRUPTED --> RECORDING: explicitly resume
  INTERRUPTED --> RECOVERABLE: app returns
  INTERRUPTED --> FINALIZING: finish stored data
  INTERRUPTED --> FAILED: recovery failure
  RECOVERABLE --> RECORDING: explicitly recover
  RECOVERABLE --> FINALIZING: finish stored data
  RECOVERABLE --> FAILED: recovery failure
  RECORDING --> FAILED: unrecoverable failure
  PAUSED --> FAILED: unrecoverable failure
  FINALIZING --> COMPLETED: summary committed
  FINALIZING --> FAILED: finalization failure
  COMPLETED --> IDLE: reset
  FAILED --> IDLE: reset
```

Diagram describes the allowed domain transitions; the normal UI may expose a subset of recovery actions. Actual foreground-service start ordering and persistence behavior must be regression-tested.
