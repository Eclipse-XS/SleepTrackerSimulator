# Architecture Remediation & System Design

## 1. Architectural Boundaries and Separation of Concerns

SleepSense 2.0 adheres to a strict four-layer architecture with explicit boundary contracts:

```
┌─────────────────────────────────────────────────────────────┐
│                     PRESENTATION LAYER                      │
│   • Jetpack Compose Screens (Today, Devices, Lab, Settings)  │
│   • ViewModels exposing immutable StateFlow                 │
│   • User gestures and navigation events                     │
└──────────────────────────────┬──────────────────────────────┘
                               │ User Commands & StateFlow
┌──────────────────────────────▼──────────────────────────────┐
│                  DOMAIN / APPLICATION LAYER                 │
│   • SessionCoordinator (Lifecycle, pause/resume/stop)       │
│   • BackgroundAutomationCoordinator (ML & sensor triggers)  │
│   • ModelSelectionEngine (TFLite vs Heuristic Fallback)     │
│   • SampleValidator & SlidingWindowSegmenter                │
└──────────────────────────────┬──────────────────────────────┘
                               │ Domain Entities & Commands
┌──────────────────────────────▼──────────────────────────────┐
│                        PLATFORM LAYER                       │
│   • RecordingService (Foreground service with health type)  │
│   • AndroidAccelerometerDataSource (Hardware SensorManager) │
│   • AndroidGattDeviceTransport (Bluetooth Low Energy Client)│
│   • AndroidSmartActuator (Vibrator & Camera2 Torch)        │
│   • VirtualGattServer (BLE Peripheral Simulator Module)     │
└──────────────────────────────┬──────────────────────────────┘
                               │ Persistence & Network Outbox
┌──────────────────────────────▼──────────────────────────────┐
│                          DATA LAYER                         │
│   • RoomSessionRepository & Room DAOs (v7 Schema)           │
│   • DataRetentionPolicy (7-day samples, 30-day windows)     │
│   • RoomCloudStateStore & WorkManager CloudOutboxWorker     │
│   • CloudSyncConsentRepository (Opt-in SharedPreferences)  │
│   • FirebaseCloudRepository (Multi-location Atomic Updates) │
└─────────────────────────────────────────────────────────────┘
```

### Presentation Layer
- **Responsibility**: Rendering UI state via Jetpack Compose declarative components and translating user actions into ViewModel intent invocations.
- **Strict Boundary**: ViewModels (`TodayViewModel`, `DevicesViewModel`, `SmartScenariosViewModel`, `SettingsViewModel`) never own background coroutine jobs, sensors, BLE transports, or actuator operations. Destroying or rotating an Activity does not interrupt background recording.

### Domain / Application Layer
- **Responsibility**: State machine coordination, signal validation, temporal windowing, on-device ML inference arbitration, and automation scenario evaluation.
- **Single Ownership**: `SessionCoordinatorImpl` holds the exclusive lock (`Mutex`) over session state transitions (`IDLE` -> `RECORDING` <-> `PAUSED` -> `COMPLETED`/`FAILED`/`INTERRUPTED`).
- **Data Provenance**: Every incoming sample carries an explicit `SourceKind` (`PHONE_REAL`, `BLE_REAL`, `SIMULATED`, `REPLAY`, `BLE_UNVERIFIED`). Provenance is immutable and cascades through feature extraction and model prediction into the final `SessionSummary`.

### Platform Layer
- **Responsibility**: Interfacing with hardware peripherals, Android OS subsystem services, and foreground service lifecycles.
- **Actuator Arbitration**: `AndroidSmartActuator` enforces bounded physical operation. Persistent vibration has a mandatory 30-second hardware auto-cutoff; all actuators are unconditionally neutralized upon session stop, pause, or fatal error via `RecordingService.stopSources()`.
- **Bluetooth Low Energy**: Adheres to the Bluetooth SIG Heart Rate Profile (0x180D). Subscribes to CCCD descriptor notifications asynchronously, serializes command characteristic writes through a `Mutex`, and confirms peripheral acknowledgement via `onCharacteristicWrite`.

### Data Layer
- **Responsibility**: ACID-compliant local storage, data lifecycle retention, and durable cloud outbox queuing.
- **Atomic Operations**: `saveSessionSummaryAndOutbox` writes the `SessionSummary`, updates the `RecordingSession` completion status, and enqueues the `PendingCloudOperation` in a single SQLite transaction.
- **Privacy Gating**: Cloud outbox queuing is strictly gated on `CloudSyncConsentRepository.isCloudSyncEnabled`. When disabled (default), zero records enter the outbox and the WorkManager synchronization worker immediately terminates.

---

## 2. Structured Concurrency & State Machine Integrity

### Lifecycle Ownership
- All background recording tasks are anchored to `SupervisorJob() + Dispatchers.Default/IO` within `RecordingService` and `SessionCoordinatorImpl`.
- UI coroutine scopes (`viewModelScope`, `rememberCoroutineScope`) are strictly restricted to UI animation, dialog state, and collecting StateFlows.

### Explicit State Machine
The session state machine enforces valid transitions:
```
                ┌───────────────┐
                │     IDLE      │
                └───────┬───────┘
                        │ startSession()
                        ▼
                ┌───────────────┐
         ┌─────►│   RECORDING   │◄─────┐
         │      └───────┬───────┘      │
resume() │              │ pause()      │ resume()
         │              ▼              │
         │      ┌───────────────┐      │
         └──────┤    PAUSED     ├──────┘
                └───────┬───────┘
                        │ stopSession() / failSession()
                        ▼
       ┌────────────────┴────────────────┐
       ▼                                 ▼
┌──────────────┐                 ┌──────────────┐
│  COMPLETED   │                 │    FAILED    │
└──────────────┘                 └──────────────┘
```
Any illegal transition (e.g., pausing an idle session, resuming an active session) immediately throws an `IllegalStateException` without corrupting persisted state.

---

## 3. Data Integrity & Retention Bounds

1. **Empty Session Handling**:
   - Recordings with 0 feature windows or 0 persisted samples are assigned `dataCoverageFraction = 0.0f` and `qualityStatus = SessionQualityStatus.INSUFFICIENT`.
   - Never awards artificial coverage or `EXCELLENT` quality to empty sessions.
2. **Pause Duration Subtraction**:
   - Accumulates `totalPausedDurationMs` across all pause/resume cycles.
   - `observationDurationMs` accurately reflects active recording time, preventing distorted movement rates.
3. **Local Retention Quotas**:
   - Raw accelerometer and heart rate samples: automatically pruned after 7 days.
   - 20-sample feature windows and ML predictions: automatically pruned after 30 days.
   - Session summaries and personal baselines: retained indefinitely until user-initiated deletion.
   - Retention execution is scheduled asynchronously upon session completion without blocking the user.
