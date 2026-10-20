# Sleep Tracker Simulator

Android educational project combining independent phone and virtual-wearable sensor streams. It is not a medical application or sleep-stage detector.

## Lab 1 — phone movement

`AndroidAccelerometerDataSource` observes the phone accelerometer. `MovementClassifier` smooths changes between acceleration vectors and exposes `STILL` or `MOVEMENT` through `SensorViewModel` and `StateFlow`. This pipeline remains live with BLE disconnected, scanning, connecting, connected, and after disconnect.

The dashboard displays the current raw X/Y/Z acceleration values in m/s² separately from derived movement intensity. Three signed axis bars are driven by the same live sample. `MainActivity.onResume()` enables collection and `onPause()` disables it; the data source unregisters its listener through `awaitClose`.

## Lab 2 — Virtual Sleep Band

`VirtualSleepBand` models an external BLE heart-rate wearable. The transport remains software-based, while its lifecycle and profile mirror a concrete BLE sensor:

- scan and discover `Sleep Band HR [VIRTUAL:HR:01]`;
- connect and discover Heart Rate Service `0x180D`;
- subscribe to Heart Rate Measurement `0x2A37`;
- receive smooth, bounded heart-rate measurements through simulated Notify;
- Read Body Sensor Location `0x2A38`;
- Write `START_STREAM`, `STOP_STREAM`, or `REQUEST_STATUS`;
- reject unknown commands.

The generator uses a bounded random walk from a configurable initial BPM. Its interval and variation source are injectable. The most recent 60 measurements feed the live Compose Canvas graph.

Protocol properties:

| UUID | Property | Meaning |
|---|---|---|
| `0x180D` | Service | Standard Heart Rate Service |
| `0x2A37` | Notify | Standard Heart Rate Measurement |
| `0x2A38` | Read | Standard Body Sensor Location |
| `7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e` | Write | Custom simulator command characteristic |

`IBleConnector.sendData(String)` is the laboratory compatibility API and delegates to the typed command parser. Unknown commands return `UNKNOWN_COMMAND`. Disconnect and reconnect clear current HR and session history so an old baseline cannot be treated as fresh.

## Combined Rest-State Estimation

`RestStateClassifier` combines phone movement, movement intensity, continuous still duration, current wearable HR, and a recent HR window. It produces:

- `MONITORING` — wearable or sensor data is unavailable, or an HR baseline is still being collected;
- `ACTIVE` — the accelerometer detects movement;
- `RESTING` — movement is low for a sustained period without enough evidence for possible sleep;
- `POSSIBLE_SLEEP` — prolonged uninterrupted stillness and stable recent HR occur together.

HR stability uses both population standard deviation and range. No absolute BPM threshold determines sleep. Classroom defaults intentionally shorten the temporal thresholds: `RESTING` after 3 seconds and `POSSIBLE_SLEEP` after 12 seconds, with at least 5 HR samples. These demonstrate transitions during a short lab session; they are not physiological thresholds.

This is an educational heuristic and not a medical sleep-stage classifier.

```text
Android Accelerometer
        |
        v
MovementClassifier
        |
        +-------------------+
                            |
                            v
                    RestStateClassifier
                            ^
                            |
Virtual Sleep Band          |
        |                   |
        v                   |
Mock BLE Connector          |
        |                   |
        v                   |
Heart Rate Measurement -----+
                            |
                            v
                       RestState
                            |
                            v
                       Compose UI
```

`SensorViewModel` and `BleViewModel` remain independent. `RestStateViewModel` combines their immutable `StateFlow` outputs without making either source ViewModel depend on the other.

## Lab 3 — Firebase Realtime Database

`CloudSyncViewModel` snapshots the latest sensor, BLE, and derived rest state every five seconds. This interval is short enough for a laboratory demonstration and long enough to avoid sending every accelerometer event. It owns one idempotent foreground producer job, while mutexes serialize session creation, queue draining, and session closure. Compose contains no Firebase calls.

Each measurement receives a UUID before it enters the queue. Firebase writes use that UUID as the key, so a retry overwrites the same logical record instead of creating duplicates. A session also has a stable UUID and is restored after process death. `End Monitoring` persists a typed `END_SESSION` operation after all earlier measurements. WorkManager schedules unique, network-constrained delivery with exponential backoff for normal process termination and supported restart scenarios. Five-second sensor sampling remains foreground-only and is never delegated to WorkManager. Android force-stop prevents scheduled work until the user launches the app again.

`value` is the smoothed movement intensity in m/s². It is duplicated as `movementIntensity` so the generic laboratory field and the domain-specific meaning are both explicit. The logical `deviceId` is `sleep-tracker-simulator`; no hardware or personal identifier is collected.

```mermaid
sequenceDiagram
    participant S as SensorManager
    participant DS as AndroidAccelerometerDataSource
    participant VM as SensorViewModel
    participant C as CloudSyncViewModel
    participant R as CloudRepository
    participant Q as Room outbox
    participant A as Firebase Anonymous Auth
    participant F as Firebase Realtime Database
    S->>DS: onSensorChanged(x, y, z)
    DS->>VM: latest SensorSample
    VM->>C: latest SensorUiState
    loop every 5 seconds
        C->>C: immutable SensorPayload snapshot
        C->>Q: persist UPLOAD_MEASUREMENT
        C->>A: ensureAuthenticated()
        C->>R: upload stable measurementId
        R->>F: setValue(payload).await()
        F-->>C: success or failure
        C->>Q: remove only after acknowledgement
        C-->>C: SYNCED, PENDING, or ERROR
    end
```

Authenticated database shape:

```text
users/{auth.uid}/sessions/{sessionId}
├── schemaVersion, deviceId, startedAt, endedAt
└── measurements/{measurementId}
    ├── measurementId, deviceId, sensorType, timestamp
    ├── x, y, z, value, movementIntensity
    └── movementState, heartRateBpm, bleConnected, restState
```

Pending operations are stored in Room `cloud_outbox` rows ordered by an auto-increment sequence and protected by a unique operation UUID. `cloud_active_session` stores the owner UID and lifecycle intent. Enqueue, acknowledgement, and END_SESSION completion use Room transactions. The queue never evicts unacknowledged data. At 10,000 telemetry operations it rejects new collection explicitly with a storage/backpressure error instead of deleting old rows. One unique terminal `END_SESSION` operation may still be appended for that session; a second terminal operation is rejected. Retry count, last error, and permanent-failure state remain durable. Existing atomic JSON state is imported idempotently on first Room access and archived only after the transaction succeeds.

Foreground and WorkManager drainers share a process-wide mutex; Firebase measurement keys remain idempotent protection across process boundaries. A UID mismatch is a permanent failure and never uploads the former owner's data under a new anonymous identity.

`SensorPayloadValidator` rejects non-finite or implausible sensor values, invalid enums, future timestamps, and malformed identifiers before queueing. Anonymous Firebase Authentication supplies the owner UID. `database.rules.json` defaults to deny, restricts telemetry to `/users/{auth.uid}`, and validates the expected schema and value ranges. No email, advertising ID, Android hardware identifier, account name, or location is collected; the fixed `deviceId` is a logical lab identifier.

Retention runs only after a completed session: completed sessions older than 30 days and completed sessions beyond the newest 30 are removed. The active session, unfinished sessions, and unrelated database branches are never selected.

Firebase Console setup required for a clean project:

1. Enable Authentication > Sign-in method > Anonymous.
2. Deploy `database.rules.json` with Firebase CLI (`firebase deploy --only database`) or paste the rules in Realtime Database > Rules and publish them.
3. Keep the configured Realtime Database region and `google-services.json` aligned with this Android application.

Cloud tests use coroutine virtual time and in-memory or temporary-file fakes. They cover scheduling, duplicate-start prevention, payload snapshots, status transitions, FIFO bounds and recovery, atomic persistence, validation boundaries, bounded retry, retention selection, cancellation, restart, and session end without contacting Firebase.

Logcat tag `CloudSync` reports successful, pending, and failed uploads without logging payload values or Firebase configuration.

## Lab 4 — Actuator Control

`DecisionEngine` receives the existing smoothed movement intensity from `SensorViewModel`. It is a pure Kotlin edge detector with an educational threshold of `0.65 m/s²`: a transition from below to `>=` the threshold while movement is classified as `MOVEMENT` produces one vibration pulse. Remaining above the threshold produces no repeated alerts; dropping below rearms it. Automatic movement alerts can be disabled without disabling manual or remote control.

```text
SensorManager → MovementClassifier → SensorViewModel → DecisionEngine
                                                        |
                                              threshold crossed?
                                                /             \
                                              no              yes
                                              none      vibration pulse
```

`AndroidSmartActuator` uses `VibratorManager` and `VibrationEffect` for pulse or persistent vibration. Flashlight control uses `CameraManager.setTorchMode()` only after checking `FEATURE_CAMERA_FLASH`, selecting a flash-capable camera, and checking camera permission. Missing hardware and API failures are represented as `UNAVAILABLE` or `ERROR`; the UI never claims that unavailable hardware is active. Automatic control uses vibration only. Flashlight is reserved for Manual Control and Firebase commands.

Cloud-to-device commands use a separate event-driven path from Lab 3 telemetry:

```text
Firebase RTDB users/{auth.uid}/control/sleep-tracker-simulator
        ↓ ValueEventListener (not polling)
FirebaseRemoteControlRepository
        ↓ callbackFlow
ActuatorViewModel
        ↓
SmartActuator → VibratorManager / CameraManager
```

The typed remote node is:

```json
{
  "users": {
    "{auth.uid}": {
      "control": {
        "sleep-tracker-simulator": {
          "vibrationEnabled": false,
          "flashlightEnabled": false
        }
      }
    }
  }
}
```

`awaitClose` removes the Firebase listener when collection is cancelled. `ActuatorViewModel.onCleared()` cancels its collectors and calls `stopAll()` so repeating vibration and torch do not remain active. `Disconnect Sleep Band` still affects only BLE; `End Monitoring` still affects only Lab 3 telemetry. Local, manual, and Firebase actuator controls remain independent of both.

Event-driven control reacts when Firebase invokes the listener after a server-side change. Polling would repeatedly ask the server whether a value changed; Lab 4 does not poll.

The actuator tests cover threshold edges and rearming, no-spam behavior, automatic enable/disable, manual and remote commands, unavailable hardware, remote errors, operation failures, and cleanup. Emulator flashlight availability depends on its virtual hardware profile; `Flashlight unavailable` is the correct result when the feature is absent.

## Verification

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

## Lab 5 — Room history and analytics

Processed `MovementAnalysis.motionIntensity` values are stored locally in Room about once per second while the activity-scoped recorder receives valid accelerometer samples. This path does not depend on BLE, Firebase authentication, cloud monitoring, or Internet connectivity.

```text
SensorManager → SensorViewModel → LocalHistoryRecorderViewModel
                                      ↓
                              SensorHistoryRepository
                                      ↓
                         Room DAO → sleep_tracker.db
                                      ↓ Flow
                              AnalyticsViewModel
                                      ↓
                         Compose Analytics + Vico
```

The `sensor_records` table contains `id`, Unix `timestamp` in milliseconds, smoothed `sensorValue` in m/s², and `actionTriggered`. Successful vibration pulses and successful transitions that enable persistent vibration or flashlight are emitted as one-shot typed activation events. Disable, unavailable, and failed operations do not create activations. A pending activation is cleared only after a Room insert succeeds.

Analytics displays a rolling one-hour window. SQL selects the newest 50 records using `timestamp DESC, id DESC`, then returns those records in chronological order for Vico. Counts cover the complete one-hour window and refresh reactively through Room `Flow`.

Additional verification commands:

```powershell
.\gradlew.bat lintDebug
.\gradlew.bat compileDebugAndroidTestSources
.\gradlew.bat connectedDebugAndroidTest
```

For Database Inspector evidence, run the debug app, open **View > Tool Windows > App Inspection**, choose **Database Inspector**, select `sleep_tracker.db`, and open `sensor_records`. The database uses WAL, so keep the app process running while inspecting it.

Educational simulation; not a medical sleep-stage detector.

## Lab 6 — On-device TFLite

`ml/train_activity_model.py` reproducibly generates synthetic accelerometer windows, trains a three-class model and exports `app/src/main/assets/activity_classifier.tflite`. The model receives `float32[1,20,3]` and returns probabilities for `REST`, `RESTLESS`, and `ACTIVE`. The committed evaluation evidence is in `docs/ml_evaluation.json`; its 97.22% test accuracy applies only to the independent synthetic test set.

`SensorWindowBuffer` maintains the latest 20 timestamp-valid XYZ samples. Sensor ingestion and inference run in separate coroutines. A bounded latest-complete-window channel prevents sensor callbacks from waiting for TFLite, records dropped stale windows and gives one serial consumer exclusive use of the Interpreter. Every prediction carries the source window timestamp and a stable prediction ID. An `ACTIVE` transition with confidence at least 0.75 can request one automatic vibration pulse. Instrumentation tests execute the packaged model, rather than replacing inference with thresholds. The exported float model is 11,368 bytes.

Reproduce the model with:

```powershell
py -3.11 -m venv ml\.venv
.\ml\.venv\Scripts\python.exe -m pip install -r ml\requirements.txt
.\ml\.venv\Scripts\python.exe ml\train_activity_model.py
```

## Lab 7 — Integrated system

The dashboard combines phone sensor state, AI Insights, virtual BLE state, heart rate, cloud/outbox status and actuator controls. Smart Rules consume unique sensor or prediction events rather than combined UI states. Before any rule side effect, Room v5 stores a `PENDING` execution snapshot. The same UUID identifies actuator results, the processed `VirtualSleepBand` alert, a durable `UPLOAD_RULE_EVENT` outbox row and `/ruleEvents/<eventId>` in Firebase. WorkManager recovers queued idempotent delivery and updates the local cloud status after acknowledgement. Architecture sources, the remediation report and defense instructions are under `docs/`.

## Privacy and backup policy

Android backup is disabled. Anonymous Firebase identity, Room history, heart-rate observations, and the pending outbox are therefore not restored into a different installation or identity context. This conservative policy prevents cross-user replay at the cost of no automatic local-data restore.
