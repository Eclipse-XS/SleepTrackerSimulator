# SleepTrackerSimulator — Lab 2 Engineering Handoff

This document is a local implementation specification for Gemini Antigravity.
It is not application source, is excluded through `.git/info/exclude`, and must not be committed.

## 1. Mission and scope

Implement Laboratory Work No. 2 in the existing `SleepTrackerSimulator` Android application.
The deliverable is a deterministic mock Bluetooth Low Energy communication layer, its state management,
and Compose controls integrated beside the existing Lab 1 sensor monitor.

Hard boundaries:

- Continue the existing application; do not create another app or module.
- Preserve the complete Lab 1 sensor pipeline and UI.
- Simulate BLE. Do not access real Bluetooth hardware or Android BLE APIs.
- Implement only Lab 2.
- Do not add Firebase, REST, Room, actuators, cameras, vibration, ML, TFLite, Airflow, or Lab 3+ work.
- Do not commit or push until the user has completed manual validation and explicitly authorizes it.

## 2. Audit facts

Audit date: 2026-09-29.

- Repository: `https://github.com/Eclipse-XS/SleepTrackerSimulator`
- Local root: `C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator`
- Current branch: `main`
- Audit-time status: clean; `main...origin/main`, no tracked or untracked production changes.
- HEAD: `d29fe9c chore: clean up repository`
- Lab 1 tag: annotated tag `lab1`
- `lab1` target: `8503e79 Lab 1: sensor monitoring and movement classification`
- Difference after the tag is repository housekeeping/documentation only: `.gitignore`, removal of `PLAN.md`, and `README.md` content.
- Application module: `app`
- Namespace/application ID: `com.example.sleeptrackersimulator`
- minSdk: 31
- targetSdk: 37
- compileSdk: Android release 37
- Java source/target: 11
- UI: Jetpack Compose Material 3
- State: Kotlin Flow/StateFlow and AndroidX ViewModel

## 3. Current relevant file tree and responsibilities

```text
SleepTrackerSimulator/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/sleeptrackersimulator/
│       │   │   ├── app/MainActivity.kt
│       │   │   ├── core/model/SensorSample.kt
│       │   │   ├── sensor/AccelerometerDataSource.kt
│       │   │   ├── sensor/AndroidAccelerometerDataSource.kt
│       │   │   ├── sensor/MovementClassifier.kt
│       │   │   ├── ui/dashboard/SensorUiState.kt
│       │   │   ├── ui/dashboard/SensorViewModel.kt
│       │   │   ├── ui/dashboard/SensorScreen.kt
│       │   │   └── ui/theme/
│       │   │       ├── Color.kt
│       │   │       └── Theme.kt
│       │   └── res/values/strings.xml
│       ├── test/java/com/example/sleeptrackersimulator/
│       │   ├── ExampleUnitTest.kt
│       │   ├── core/model/SensorSampleTest.kt
│       │   └── sensor/MovementClassifierTest.kt
│       └── androidTest/java/com/example/sleeptrackersimulator/
│           └── ExampleInstrumentedTest.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── gradle.properties
└── README.md
```

### `app/src/main/java/com/example/sleeptrackersimulator/app/MainActivity.kt`

- Purpose: single Activity and Compose entry point.
- Main API: `MainActivity : ComponentActivity`.
- Current dependencies: `AndroidAccelerometerDataSource`, `SensorViewModel`, `SensorScreen`, `SleepTrackerTheme`.
- Current behavior: creates `SensorViewModel` with its custom factory, lifecycle-collects `uiState`, and passes it to `SensorScreen`.
- Lab 2 change allowed: yes. Add `BleViewModel`, collect BLE state/logs, and pass BLE event callbacks to `SensorScreen`.
- Do not replace the existing sensor ViewModel or collection.

### `app/src/main/java/com/example/sleeptrackersimulator/core/model/SensorSample.kt`

- Defines `SensorSample`, computed Euclidean `magnitude`, `MovementState`, and `MovementAnalysis`.
- Used by data source, classifier, ViewModel, UI, and tests.
- Lab 2 change: none.

### `app/src/main/java/com/example/sleeptrackersimulator/sensor/AccelerometerDataSource.kt`

- Defines `AccelerometerEvent` and `AccelerometerDataSource.observe(): Flow<AccelerometerEvent>`.
- Its contract guarantees listener registration while collected and unregistering when collection stops.
- Lab 2 change: none.

### `app/src/main/java/com/example/sleeptrackersimulator/sensor/AndroidAccelerometerDataSource.kt`

- Obtains `SensorManager` and the default `TYPE_ACCELEROMETER`.
- Uses `callbackFlow`, a `SensorEventListener`, `trySend`, and `awaitClose`.
- Registers at `SENSOR_DELAY_UI`; unregisters in `awaitClose`.
- Emits `Unavailable` if no sensor/manager and `Error` for registration failures.
- Lab 2 change: none.

### `app/src/main/java/com/example/sleeptrackersimulator/sensor/MovementClassifier.kt`

- Calculates 3D delta between consecutive acceleration vectors.
- Applies exponential smoothing and hysteresis.
- Defaults: movement `0.45f`, still `0.20f`, smoothing `0.25f`.
- `reset()` restores STILL, zero intensity, and no previous sample.
- Lab 2 change: none.

### `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/SensorUiState.kt`

- Sensor-only sealed state: `Initializing`, `Active`, `Unavailable`, `Error`.
- Lab 2 change: none. BLE state must remain separate.

### `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/SensorViewModel.kt`

- Maps `AccelerometerEvent` to `SensorUiState`.
- Runs in `viewModelScope` via `stateIn`.
- Uses `SharingStarted.WhileSubscribed(stopTimeoutMillis = 0)` so UI collection controls sensor registration.
- Resets classifier at collection start and maps exceptions to UI errors.
- Lab 2 change: none. Do not merge unrelated BLE responsibilities into it.

### `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/SensorScreen.kt`

- Current single scrollable screen.
- Shows title/disclaimer, sensor status, movement state, X/Y/Z, magnitude, graphical position/motion indicator, filtered motion, and thresholds.
- Lab 2 change allowed: yes. Extend its public parameters and append a BLE section after the existing sensor content.
- Preserve all existing sensor composables and text.

### `ui/theme/Color.kt` and `Theme.kt`

- Define light/dark Material 3 colors and `SleepTrackerTheme`.
- Lab 2 change: none; use the current Material color scheme.

### `app/src/main/AndroidManifest.xml`

- Contains only the application and exported launcher Activity; no permissions exist.
- Lab 2 change required by methodology: add BLE permission declarations described below.
- Runtime permission handling is not required for the mock implementation.

### Gradle configuration

- Version catalog contains Compose, lifecycle, coroutines Android, JUnit 4, AndroidX test, Espresso.
- `app/build.gradle.kts` already has ViewModel Compose and coroutine runtime.
- It does not contain `kotlinx-coroutines-test`.
- Lab 2 change: add `kotlinx-coroutines-test` as a test dependency so delayed mock transitions can be tested with virtual time.

### Existing tests

- `SensorSampleTest`: magnitude uses all axes.
- `MovementClassifierTest`: gravity-only stillness, movement threshold, hysteresis, and reset.
- `ExampleUnitTest`: template arithmetic test.
- `ExampleInstrumentedTest`: application package assertion.
- Lab 2 change: do not edit these. Add dedicated BLE tests.

## 4. Actual Lab 1 data flow

```text
Android SensorManager / TYPE_ACCELEROMETER
                |
                v
AndroidAccelerometerDataSource.observe()
                |
                v
callbackFlow<AccelerometerEvent>
                |
                v
SensorViewModel + MovementClassifier.analyze()
                |
                v
StateFlow<SensorUiState>
                |
                v
MainActivity.collectAsStateWithLifecycle()
                |
                v
SensorScreen / ActiveSensorContent
```

## LAB 1 FROZEN FUNCTIONAL BASELINE

The following behavior is frozen.

- The app acquires `Sensor.TYPE_ACCELEROMETER` through `SensorManager`.
- X, Y, and Z are read from indices 0, 1, and 2 of each sensor event.
- `SensorSample.magnitude` is `sqrt(x² + y² + z²)`.
- UI presents X, Y, Z, acceleration magnitude, and units `m/s²`.
- UI presents a graphical circle/crosshair, a position bubble derived from X/Y, and an intensity arc.
- The classifier labels the current state `STILL` or `MOVEMENT`.
- Raw motion is the Euclidean distance between consecutive 3D acceleration vectors.
- Smoothed intensity follows `s += 0.25 * (raw - s)` by default.
- Transition STILL → MOVEMENT occurs at intensity `>= 0.45 m/s²`.
- Transition MOVEMENT → STILL occurs at intensity `<= 0.20 m/s²`.
- Separate thresholds provide hysteresis and prevent boundary flicker.
- First sample produces raw intensity zero.
- `reset()` returns the classifier to STILL and clears history.
- Sensor registration is collection-bound and lifecycle-safe through `callbackFlow`/`awaitClose` and lifecycle-aware state collection.
- Emulator Virtual Sensors can change X/Y/Z and therefore update the UI/classification.
- Device Pose changes the accelerometer vector and position bubble.
- The screen is vertically scrollable and survives compact/rotated layouts without intentionally removing content.
- Initializing, unavailable, and sensor-error presentations remain.
- The educational/non-medical disclaimer remains.

DO NOT REMOVE.
DO NOT REPLACE.
DO NOT SIMPLIFY.
DO NOT CHANGE THESE VALUES UNLESS REQUIRED BY THE ACTUAL LAB 2 SPECIFICATION.

Lab 2 has no requirement that justifies changing this baseline.

## LAB 2 REQUIREMENTS FROM METHODOLOGY

### REQ-01 — BLE device profile

- Source requirement: define UUIDs for a service and characteristics, including status/read-notify and command/write semantics.
- Implementation interpretation: define one service UUID, status characteristic UUID, and command characteristic UUID in `BleProfile`.
- Verification: inspect constants and include them in the report protocol table.

### REQ-02 — Communication abstraction

- Source requirement: create Kotlin interface `IBleConnector` with `connect()`, `disconnect()`, and `sendData(data: String)`.
- Interpretation: expose these exact operations plus immutable state/log flows needed for StateFlow architecture.
- Verification: interface exists and ViewModel depends on it rather than concrete mock.

### REQ-03 — Mock scanner

- Source requirement: `MockBleScanner` finds the virtual IoT device after approximately two seconds.
- Interpretation: a dedicated scanner exposes a suspending deterministic `scan()` returning one fixed `BleDevice` after `2_000 ms`.
- Verification: virtual-time unit test asserts no result before and result at 2 seconds.

### REQ-04 — Scan control

- Source requirement: button “Пошук пристроїв”.
- Interpretation: visible scan button labelled `Search for devices` in this English UI.
- Verification: tapping invokes `BleViewModel.startScan()`.

### REQ-05 — Scan progress

- Source requirement: visualize scanning with a ProgressBar.
- Interpretation: show `CircularProgressIndicator` and `Scanning…` while state is Scanning.
- Verification: visible during the two-second delay.

### REQ-06 — Discovered devices

- Source requirement: display a list with at least one virtual device.
- Interpretation: render a list container from `discoveredDevices`; mock returns exactly one device.
- Verification: device name and address appear after scanning.

### REQ-07 — Connection lifecycle

- Source requirement: connection status Connected/Disconnected and connection/disconnection controls.
- Interpretation: Connect on a found device, deterministic Connecting delay, Connected state, Disconnect action.
- Verification: status badge and action button follow state transitions.

### REQ-08 — Data write

- Source requirement: simulate `sendData(data: String)` / Write.
- Interpretation: a text field and Send button are enabled while connected; nonblank payload is logged.
- Verification: send `STATUS_REQUEST`; both Logcat and on-screen terminal show TX entry.

### REQ-09 — Read/Notify semantics

- Source requirement: emulate exchange Read/Write/Notify.
- Interpretation: on connection, mock logs a status read/notification using the status characteristic; after a command, emit deterministic ACK notification.
- Verification: on-screen terminal contains connected/status and TX/RX ACK lines.

### REQ-10 — Logging

- Source requirement: output simulated exchange in Logcat and an in-app terminal.
- Interpretation: `MockBleConnector` calls `Log.d(TAG, message)` and appends the same message to bounded StateFlow logs.
- Verification: send packet and inspect both places.

### REQ-11 — Reactive state

- Source requirement: StateFlow or LiveData.
- Interpretation: use StateFlow throughout BLE layer/ViewModel because the project already uses Flow.
- Verification: UI lifecycle-collects immutable BLE StateFlows.

### REQ-12 — Permissions

- Source requirement: declare `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, and legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` for API below 31.
- Interpretation: add modern permissions; add legacy and location with `maxSdkVersion="30"` even though minSdk 31 means they will not apply to supported installs.
- Verification: manifest inspection and lint.

### REQ-13 — Preserve Lab 1

- Source context: Lab 2 extends the Lab 1 app.
- Interpretation: append BLE UI and create a separate BLE ViewModel.
- Verification: regression checklist and manual Virtual Sensors test.

## 5. Target architecture

Use a separate `BleViewModel`. Sensor and BLE lifecycles are independent, and merging them would risk the frozen sensor pipeline.
Keep the mock layer small: models/state, scanner, connector, ViewModel, UI section.

```text
MainActivity
├── SensorViewModel ──> AccelerometerDataSource ──> SensorManager
│       └── StateFlow<SensorUiState>
└── BleViewModel ──> IBleConnector ──> MockBleConnector
        │                                  └── MockBleScanner
        ├── StateFlow<BleUiState>                └── delay(2 seconds) -> BleDevice
        └── StateFlow<List<String>>

SensorScreen
├── existing Lab 1 content (unchanged behavior)
└── BleSection(state, logs, callbacks)
```

## 6. BLE profile and deterministic mock specification

All values below are implementation choices unless the methodology names them.

| Item | Value | Role |
|---|---|---|
| Device name | `Sleep Tracker Hub` | Discovered virtual IoT device |
| Address/ID | `MOCK:00:11:22:33:44` | Stable display identifier, not a hardware MAC |
| Service UUID | `0000a000-0000-1000-8000-00805f9b34fb` | Sleep tracker BLE service |
| Status UUID | `0000a001-0000-1000-8000-00805f9b34fb` | Read/Notify status |
| Command UUID | `0000a002-0000-1000-8000-00805f9b34fb` | Write commands |
| Discovery delay | `2_000 ms` | Required approximate scan delay |
| Connection delay | `750 ms` | Visible deterministic Connecting state |
| Initial notification | `STATUS:READY` | Simulated status read/notify |
| Command acknowledgement | `ACK:<payload>` | Deterministic response |
| Log capacity | 50 entries | Avoid unbounded memory growth |

No random failures. Error state exists for safe exception mapping and invalid connector failures, but the standard mock path succeeds.

## 7. BLE state model

Use one sealed interface:

- `Idle`: no scan has run.
- `Scanning`: scan delay is active.
- `DeviceFound(devices)`: one or more devices discovered; disconnected.
- `Connecting(device)`: connection delay active.
- `Connected(device)`: commands allowed.
- `Disconnected(device?)`: explicitly disconnected or a scan was cancelled/replaced.
- `Error(message)`: operation failed; user may scan again.

```text
Idle/Disconnected/Error/DeviceFound
             | startScan()
             v
          Scanning
             | 2 seconds
             v
        DeviceFound([device])
             | connect(device)
             v
         Connecting
             | 750 ms
             v
          Connected
        | sendData(nonblank) -> remains Connected + TX/RX logs
        | disconnect()
        v
       Disconnected
```

Rules:

- `startScan()` while Scanning is ignored.
- A new valid scan cancels any stale scan/connection job, disconnects the connector if necessary, clears old devices, and enters Scanning.
- Connect is accepted only for a currently discovered device and only from DeviceFound.
- Connect during Connecting/Connected is ignored.
- Disconnect while Connected/Connecting cancels connection work and produces Disconnected.
- Disconnect while already disconnected is idempotent.
- Blank data is rejected by ViewModel; do not log it as sent.
- Send outside Connected produces no connector write; ViewModel may expose a short error log.
- ViewModel `onCleared()` cancels `viewModelScope`; connector cleanup must cancel internal jobs and leave no callbacks.
- Repeated scan after disconnect must rediscover exactly one device, not append duplicates.

## 8. File-by-file implementation plan

### Files to create

#### `app/src/main/java/com/example/sleeptrackersimulator/ble/BleModels.kt`

- Type: Kotlin models/state.
- Purpose: immutable BLE device, profile, and connection state definitions.
- Define: `BleDevice`, `BleConnectionState`, `BleProfile`.
- Dependencies: Kotlin/JDK `UUID`; no Android dependency.
- Used by: scanner, connector, ViewModel, UI, tests.
- Tests: state equality and constants need no dedicated test; exercised by connector/ViewModel tests.

Skeleton:

```kotlin
data class BleDevice(val name: String, val address: String)

sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data class DeviceFound(val devices: List<BleDevice>) : BleConnectionState
    data class Connecting(val device: BleDevice) : BleConnectionState
    data class Connected(val device: BleDevice) : BleConnectionState
    data class Disconnected(val device: BleDevice? = null) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

object BleProfile {
    val SERVICE_UUID: UUID = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
}
```

#### `app/src/main/java/com/example/sleeptrackersimulator/ble/IBleConnector.kt`

- Type: interface.
- Purpose: communication abstraction explicitly required by methodology.
- Properties: immutable state and logs.
- Methods: scan, exact required connect/disconnect/sendData operations, cleanup.
- Dependencies: `StateFlow`.
- Used by: `BleViewModel`; implemented by `MockBleConnector`.

Skeleton:

```kotlin
interface IBleConnector {
    val state: StateFlow<BleConnectionState>
    val logs: StateFlow<List<String>>
    fun startScan()
    fun connect(device: BleDevice)
    fun disconnect()
    fun sendData(data: String)
    fun close()
}
```

The methodology names `connect()`. Accepting the discovered `BleDevice` is a minimal necessary refinement for a displayed device list. If strict marking expects a zero-argument signature, use `connect()` and store the sole discovered device internally; do not expose both overloads without reason. Prefer `connect(device)` because UI selection is explicit, and document it in the report.

#### `app/src/main/java/com/example/sleeptrackersimulator/ble/MockBleScanner.kt`

- Type: deterministic mock service.
- Purpose: find the fixed device after two seconds.
- Method: `suspend fun scan(): List<BleDevice>`.
- Behavior: `delay(discoveryDelayMillis)`, then return `listOf(VIRTUAL_DEVICE)`.
- Cancellation: coroutine cancellation must propagate; do not catch `CancellationException`.
- Dependency: kotlinx.coroutines.

Skeleton:

```kotlin
class MockBleScanner(
    private val discoveryDelayMillis: Long = 2_000L,
) {
    suspend fun scan(): List<BleDevice> {
        delay(discoveryDelayMillis)
        return listOf(VIRTUAL_DEVICE)
    }

    companion object {
        val VIRTUAL_DEVICE = BleDevice("Sleep Tracker Hub", "MOCK:00:11:22:33:44")
    }
}
```

#### `app/src/main/java/com/example/sleeptrackersimulator/ble/MockBleConnector.kt`

- Type: `IBleConnector` implementation.
- Purpose: own mock jobs, state transitions, Logcat and terminal logs.
- Constructor: scanner, scope, connection delay; use defaults in production and injectable values/scope in tests.
- State: private `MutableStateFlow`, public `asStateFlow`; same for bounded logs.
- Jobs: one `operationJob` for scan/connect.
- Threading: use injected scope backed by `SupervisorJob() + Dispatchers.Main.immediate` in production, or have ViewModel own orchestration. Preferred design below makes connector own its scope and `close()` it.
- Logging: `Log.d("MockBleConnector", message)` and append to terminal.
- Tests: transition tests with `StandardTestDispatcher`/test scope.

Skeleton:

```kotlin
class MockBleConnector(
    private val scanner: MockBleScanner = MockBleScanner(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val connectionDelayMillis: Long = 750L,
) : IBleConnector {
    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val state = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs = _logs.asStateFlow()
    private var operationJob: Job? = null

    override fun startScan() {
        if (_state.value is BleConnectionState.Scanning) return
        operationJob?.cancel()
        _state.value = BleConnectionState.Scanning
        log("SCAN started")
        operationJob = scope.launch {
            try {
                val devices = scanner.scan()
                _state.value = BleConnectionState.DeviceFound(devices)
                devices.forEach { log("FOUND ${it.name} [${it.address}]") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = BleConnectionState.Error(error.message ?: "BLE scan failed")
                log("ERROR scan failed")
            }
        }
    }

    override fun connect(device: BleDevice) {
        val found = (_state.value as? BleConnectionState.DeviceFound)?.devices.orEmpty()
        if (device !in found) return
        operationJob?.cancel()
        _state.value = BleConnectionState.Connecting(device)
        log("CONNECTING ${device.name}")
        operationJob = scope.launch {
            delay(connectionDelayMillis)
            _state.value = BleConnectionState.Connected(device)
            log("CONNECTED ${device.name}")
            log("NOTIFY ${BleProfile.STATUS_CHARACTERISTIC_UUID}: STATUS:READY")
        }
    }

    override fun disconnect() {
        operationJob?.cancel()
        val device = when (val current = _state.value) {
            is BleConnectionState.Connected -> current.device
            is BleConnectionState.Connecting -> current.device
            else -> null
        }
        _state.value = BleConnectionState.Disconnected(device)
        log("DISCONNECTED${device?.let { " ${it.name}" }.orEmpty()}")
    }

    override fun sendData(data: String) {
        if (_state.value !is BleConnectionState.Connected || data.isBlank()) return
        val payload = data.trim()
        log("TX ${BleProfile.COMMAND_CHARACTERISTIC_UUID}: $payload")
        log("RX ${BleProfile.STATUS_CHARACTERISTIC_UUID}: ACK:$payload")
    }

    override fun close() {
        operationJob?.cancel()
        scope.cancel()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logs.update { (it + message).takeLast(MAX_LOG_LINES) }
    }
}
```

Do not add timestamps unless tests inject a clock; nondeterministic formatted times complicate assertions and are unnecessary.

#### `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/BleViewModel.kt`

- Type: AndroidX ViewModel.
- Purpose: UI-facing facade over `IBleConnector`; no Android BLE APIs.
- Constructor dependency: `IBleConnector`.
- Expose: connector `state` and `logs` as immutable StateFlow.
- Actions: `startScan`, `connect`, `disconnect`, `sendData`.
- Validation: trim payload and reject blank values.
- Cleanup: `onCleared()` calls connector `close()`.
- Factory: companion `factory(connector)` matching current project style.

Skeleton:

```kotlin
class BleViewModel(private val connector: IBleConnector) : ViewModel() {
    val state: StateFlow<BleConnectionState> = connector.state
    val logs: StateFlow<List<String>> = connector.logs

    fun startScan() = connector.startScan()
    fun connect(device: BleDevice) = connector.connect(device)
    fun disconnect() = connector.disconnect()
    fun sendData(data: String) {
        if (data.isNotBlank()) connector.sendData(data.trim())
    }

    override fun onCleared() {
        connector.close()
        super.onCleared()
    }

    companion object { fun factory(connector: IBleConnector): ViewModelProvider.Factory = ... }
}
```

#### `app/src/test/java/com/example/sleeptrackersimulator/ble/MockBleScannerTest.kt`

- Test scanner delay and deterministic result using `runTest` and virtual time.
- Tests specified in section 15.

#### `app/src/test/java/com/example/sleeptrackersimulator/ble/MockBleConnectorTest.kt`

- Test all connector transitions, repeat behavior, invalid transitions, logs, and cancellation.
- Use a test scope passed to connector; never wait real seconds.

#### `app/src/test/java/com/example/sleeptrackersimulator/ui/dashboard/BleViewModelTest.kt`

- Test delegation, blank-input rejection, and cleanup using a small fake connector.
- This is optional only if connector tests cover all observable behavior; preferred for clear ViewModel responsibility.

### Files to modify

#### `app/src/main/java/com/example/sleeptrackersimulator/app/MainActivity.kt`

Add:

```kotlin
private val bleViewModel: BleViewModel by viewModels {
    BleViewModel.factory(MockBleConnector())
}
```

Inside `setContent`, lifecycle-collect sensor state, BLE state, and logs separately. Pass callbacks:

```kotlin
SensorScreen(
    uiState = sensorState,
    bleState = bleState,
    bleLogs = bleLogs,
    onScan = bleViewModel::startScan,
    onConnect = bleViewModel::connect,
    onDisconnect = bleViewModel::disconnect,
    onSendData = bleViewModel::sendData,
    modifier = Modifier.fillMaxSize(),
)
```

Do not create connector inside a Composable; Activity ViewModel factory owns the stable instance.

#### `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/SensorScreen.kt`

Extend signature with BLE state/logs/callbacks. Append `BleSection` after the existing sensor `when` block so BLE controls remain visible even when the accelerometer is unavailable.

Suggested signature:

```kotlin
fun SensorScreen(
    uiState: SensorUiState,
    bleState: BleConnectionState,
    bleLogs: List<String>,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSendData: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```

Keep payload input as `rememberSaveable { mutableStateOf("") }`; clear only after a successful Send click while connected.

#### `app/src/main/AndroidManifest.xml`

Insert immediately inside `<manifest>` and before `<application>`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

These declarations satisfy methodology/report expectations. The mock never calls protected Bluetooth APIs, so do not request these permissions at runtime and do not block mock scanning on grant state.

#### `gradle/libs.versions.toml`

Add:

```toml
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

#### `app/build.gradle.kts`

Add:

```kotlin
testImplementation(libs.kotlinx.coroutines.test)
```

No BLE library, DI framework, navigation library, or additional Compose dependency is required.

### Files that must remain unchanged

- `core/model/SensorSample.kt`
- `sensor/AccelerometerDataSource.kt`
- `sensor/AndroidAccelerometerDataSource.kt`
- `sensor/MovementClassifier.kt`
- `ui/dashboard/SensorUiState.kt`
- `ui/dashboard/SensorViewModel.kt`
- `ui/theme/Color.kt`
- `ui/theme/Theme.kt`
- all current Lab 1 unit/instrumentation tests
- root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- `README.md` during implementation unless the user explicitly requests documentation changes

## 9. Coroutine and StateFlow rules

- One active scan/connect `Job` maximum.
- `startScan()` checks Scanning before cancellation so double taps do not restart the two-second clock.
- Valid repeated scan cancels stale operations and clears old result by entering Scanning.
- Do not use `Thread.sleep`; use suspending `delay`.
- Do not swallow `CancellationException`.
- Expose flows via `asStateFlow`; never expose `MutableStateFlow`.
- Use atomic `_logs.update` and `takeLast(50)`.
- Perform state mutation from the single main dispatcher scope; tests substitute a deterministic test dispatcher.
- `close()` is idempotent and cancels the connector scope.
- Lifecycle collection in Activity uses `collectAsStateWithLifecycle` exactly as sensor state does.
- Do not use `GlobalScope`.

## 10. Concrete Compose UI specification

Retain the existing `Scaffold`, scrollable `Column`, heading, disclaimer, sensor status/content, and threshold text. Append:

```text
SensorScreen
├── existing title and disclaimer
├── existing Lab 1 state/content
└── BLE section
    ├── Text: “Mock BLE connection”
    ├── status Card / badge
    ├── Button: “Search for devices”
    ├── Scanning progress row
    ├── discovered-device list
    │   └── Card: name, address, Connect button
    ├── connected command controls
    │   ├── OutlinedTextField (label “Command”)
    │   └── Button “Send”
    ├── Button “Disconnect” where applicable
    └── terminal Card
        ├── Text “BLE terminal”
        └── log lines or “No BLE messages yet”
```

Element rules:

| Element | Visible | Enabled | Handler/source |
|---|---|---|---|
| Section title | Always | n/a | static |
| Status | Always | n/a | derived from `BleConnectionState` |
| Search button | Idle, DeviceFound, Disconnected, Error | yes | `onScan` |
| Search button | Scanning, Connecting, Connected | either hidden or disabled; choose disabled for discoverability | `onScan` |
| Progress indicator | Scanning | n/a | state |
| Device cards | DeviceFound | yes | state devices |
| Connect button | DeviceFound | yes | `onConnect(device)` |
| Connecting label/progress | Connecting | n/a | state |
| Disconnect button | Connecting or Connected | yes | `onDisconnect` |
| Command field | Connected | yes | local saveable state |
| Send button | Connected and trimmed command nonblank | yes | `onSendData`; clear input |
| Terminal | Always | n/a | `bleLogs` |

Use Material 3 `Card`, `Button`, `OutlinedButton`, `OutlinedTextField`, `CircularProgressIndicator`, `Text`, `Column`, and `Row`. Do not add navigation. Keep touch targets and spacing conventional. Avoid fixed terminal heights that clip text; show the last 50 lines in the existing outer scroll.

## 11. Expected UI per state

### Idle

- Status: `Disconnected`.
- Search enabled.
- No device card.
- Terminal says `No BLE messages yet`.

### Scanning

- Status: `Scanning`.
- Progress indicator and `Scanning for virtual devices…`.
- Search disabled.
- No stale device card.

### DeviceFound

- Status: `Device found`.
- Card shows `Sleep Tracker Hub` and `MOCK:00:11:22:33:44`.
- Connect enabled; Search remains available for repeat scan.

### Connecting

- Status: `Connecting`.
- Device identity visible.
- Small progress indicator; Disconnect enabled; Send hidden.

### Connected

- Status: `Connected` with device name.
- Disconnect enabled.
- Command field and Send available.
- Terminal already shows CONNECTED and `STATUS:READY` notification.

### Disconnected

- Status: `Disconnected`.
- Search enabled.
- Command controls hidden.
- Prior logs remain visible as evidence.

### Error

- Status: `Error` and message.
- Search enabled for recovery.
- Do not crash or erase terminal history.

## 12. Permission analysis

### A. Real BLE on Android

For Android 12/API 31+, scanning/connecting normally requires runtime `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. For API 30 and below, legacy Bluetooth permissions and commonly fine location were used for scanning. `neverForLocation` states scanning is not used to derive physical location but can filter certain beacons in a real implementation.

### B. Mock BLE technical requirements

No permission is technically required. The implementation uses delay, flows, and logs only. It must work even if Bluetooth is off and without runtime permission dialogs.

### C. Methodology/report expectations

Declare all listed permissions. Because actual minSdk is 31, legacy declarations with maxSdk 30 never apply to supported devices but accurately document compatibility requirements. Do not lower minSdk and do not implement runtime permission UI for a mock that never reaches Android Bluetooth APIs.

## 13. Gradle decision

Add only `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2` via the existing version catalog. This enables virtual-time tests. All production facilities are already present. No BLE, DI, serialization, or logging dependency is needed.

## 14. Unit test specification

### `MockBleScannerTest`

Test: `scan returns no result before discovery delay and fixed device after delay`

- Arrange: `runTest`, scanner with 2,000 ms.
- Act: launch async scan; call `runCurrent`, advance by 1,999 ms, then 1 ms.
- Assert: deferred incomplete before 2,000; completed afterward; result exactly one fixed device.

Test: `cancelled scan does not return a device`

- Arrange: launch scanner in test scope.
- Act: cancel before advancing time.
- Assert: job cancelled and no completion callback/result consumed.

### `MockBleConnectorTest`

Test: `initial state is idle`

- Arrange: connector with test scope.
- Act: none.
- Assert: state is Idle and logs empty.

Test: `start scan immediately exposes scanning`

- Arrange: connector.
- Act: `startScan()`, `runCurrent()`.
- Assert: state Scanning; logs contain SCAN.

Test: `device appears after two seconds`

- Arrange: start scan.
- Act: advance 1,999 then 1 ms.
- Assert: Scanning before boundary; DeviceFound afterward with exactly fixed device.

Test: `connect transitions through connecting to connected`

- Arrange: discover fixed device.
- Act: connect; inspect immediately; advance 750 ms.
- Assert: Connecting then Connected; logs contain CONNECTING, CONNECTED, STATUS:READY.

Test: `send data logs write and notification ack`

- Arrange: connected.
- Act: `sendData("STATUS_REQUEST")`.
- Assert: state remains Connected; TX command UUID and RX/NOTIFY status UUID with ACK appear.

Test: `disconnect cancels connection and exposes disconnected`

- Arrange: enter Connecting.
- Act: disconnect before 750 ms; advance until idle.
- Assert: Disconnected; never changes later to Connected.

Test: `disconnect after connected preserves device identity`

- Arrange: connected.
- Act: disconnect.
- Assert: `Disconnected(device)` and DISCONNECTED log.

Test: `double scan while scanning does not restart delay`

- Arrange: scan, advance 1,000 ms.
- Act: call scan again, advance another 1,000 ms.
- Assert: discovery completes at original 2,000 ms and only one FOUND line exists.

Test: `repeated scan replaces devices rather than duplicating`

- Arrange: complete scan, scan again.
- Act: advance second delay.
- Assert: DeviceFound list size remains one.

Test: `connect without discovery is ignored`

- Arrange: Idle connector and arbitrary device.
- Act: connect.
- Assert: state stays Idle, no CONNECTED log.

Test: `send while disconnected is ignored`

- Arrange: Idle/Disconnected.
- Act: send nonblank payload.
- Assert: no TX/RX log.

Test: `close cancels pending scan`

- Arrange: begin scan.
- Act: close, advance time.
- Assert: no DeviceFound after closure.

Test: `terminal history is bounded`

- Arrange: connected.
- Act: send enough messages to exceed 50 log entries.
- Assert: list size is 50 and newest lines remain.

### `BleViewModelTest`

Test: `actions delegate to connector`

- Arrange: fake connector recording calls.
- Act: scan, connect, disconnect.
- Assert: one correct call for each.

Test: `blank command is not delegated`

- Arrange: fake connector.
- Act: send blank/whitespace.
- Assert: no send call.

Test: `command is trimmed before delegation`

- Arrange: fake connector.
- Act: send `  STATUS_REQUEST  `.
- Assert: payload equals `STATUS_REQUEST`.

Test: `clearing ViewModel closes connector`

- Arrange: test-visible subclass/helper or test the connector lifecycle through factory where practical.
- Act: clear ViewModel.
- Assert: fake `close()` called once.

## 15. Lab 1 regression plan

### REG-01 — Launch

- Action: cold-launch app.
- Expected: no crash; existing title/disclaimer and sensor UI appear; BLE section is appended.

### REG-02 — X/Y/Z

- Action: Emulator Extended controls → Virtual Sensors → Device Pose; change pose.
- Expected: all three values update.

### REG-03 — Magnitude

- Action: change pose.
- Expected: magnitude updates and remains numerically consistent with axes.

### REG-04 — Graphical indicator

- Action: tilt X/Y.
- Expected: bubble moves inside circular indicator and arc represents filtered motion.

### REG-05 — STILL

- Action: hold pose stable.
- Expected: filtered motion falls to/below 0.20 and state becomes STILL.

### REG-06 — MOVEMENT

- Action: make a sufficiently abrupt pose change.
- Expected: filtered motion reaches/ exceeds 0.45 and state becomes MOVEMENT.

### REG-07 — Hysteresis

- Action: stop movement after MOVEMENT.
- Expected: state does not flicker at intermediate values; returns at/below 0.20.

### REG-08 — Device Pose compatibility

- Action: use emulator Virtual Sensors throughout scan/connect/send.
- Expected: sensor UI keeps updating while BLE operations proceed.

### REG-09 — Rotation

- Action: rotate portrait/landscape and scroll.
- Expected: no crash or inaccessible content; ViewModels retain active state; input uses saveable state.

### REG-10 — Listener lifecycle

- Action: background then foreground the app.
- Expected: no duplicate listener symptoms; updates resume; no crash.

## 16. Exact implementation sequence for Antigravity

### STEP 01
- Files: repository/Git.
- Action: run `git status --short --branch`, `git log -5 --oneline --decorate`, `git tag --list`.
- Expected: known `main`, `lab1`; identify any user changes.
- Validation: do not overwrite unrelated changes.

### STEP 02
- Files: all files listed in section 3.
- Action: reread actual code and compare against this handoff.
- Expected: no silent divergence.
- Validation: update implementation only when actual repository evidence requires it.

### STEP 03
- Files: `BleModels.kt`.
- Action: create models, sealed state, and UUID profile exactly as specified.
- Expected: pure Kotlin BLE model layer.
- Validation: IDE/compiler resolves UUID.

### STEP 04
- Files: `IBleConnector.kt`.
- Action: create connector contract with immutable flows/actions.
- Expected: ViewModel can depend on abstraction.
- Validation: no Android Bluetooth type in interface.

### STEP 05
- Files: `MockBleScanner.kt`.
- Action: implement fixed device and suspending 2-second scan.
- Expected: deterministic single-device discovery.
- Validation: cancellation not caught.

### STEP 06
- Files: version catalog, app Gradle.
- Action: add coroutines-test dependency only.
- Expected: unit tests can use virtual time.
- Validation: Gradle sync / `testDebugUnitTest` dependency resolution.

### STEP 07
- Files: `MockBleScannerTest.kt`.
- Action: implement delay boundary and cancellation tests.
- Expected: scanner contract locked before connector work.
- Validation: `./gradlew.bat testDebugUnitTest --tests "*MockBleScannerTest"`.

### STEP 08
- Files: `MockBleConnector.kt`.
- Action: implement state/log flows, one operation job, scan/connect/disconnect/send/close.
- Expected: state machine matches section 7.
- Validation: compile; no blocking calls or GlobalScope.

### STEP 09
- Files: `MockBleConnectorTest.kt`.
- Action: implement all transition/cancellation/repeat/log tests.
- Expected: deterministic virtual-time suite.
- Validation: run that test class.

### STEP 10
- Files: BLE files.
- Action: inspect API and eliminate exposed mutable state and duplicate job paths.
- Expected: coherent mock layer.
- Validation: Checkpoint A/B below.

### STEP 11
- Files: `BleViewModel.kt`.
- Action: add facade, validation, cleanup, and factory.
- Expected: UI has a small lifecycle owner.
- Validation: compile ViewModel without touching SensorViewModel.

### STEP 12
- Files: `BleViewModelTest.kt`.
- Action: implement fake-based delegation/validation/cleanup tests.
- Expected: ViewModel responsibilities verified.
- Validation: run test class.

### STEP 13
- Files: `MainActivity.kt`.
- Action: instantiate BLE ViewModel, collect its two flows lifecycle-safely, pass state and callbacks.
- Expected: both independent ViewModels coexist.
- Validation: Kotlin compilation succeeds.

### STEP 14
- Files: `SensorScreen.kt`.
- Action: extend signature without removing any Lab 1 parameter/content.
- Expected: call site compiles before detailed UI extraction.
- Validation: search diff for accidental deletion of current sensor composables.

### STEP 15
- Files: `SensorScreen.kt`.
- Action: add `BleSection` and status mapping.
- Expected: idle/scanning/device/connecting/connected/disconnected/error render distinctly.
- Validation: Compose compilation.

### STEP 16
- Files: `SensorScreen.kt`.
- Action: add fixed device list card and Connect action.
- Expected: found device name/address visible.
- Validation: state-driven visibility only.

### STEP 17
- Files: `SensorScreen.kt`.
- Action: add saveable command input, Send, Disconnect.
- Expected: Send only connected/nonblank; input clears after send.
- Validation: handlers call passed callbacks, not connector directly.

### STEP 18
- Files: `SensorScreen.kt`.
- Action: add bounded terminal rendering and empty state.
- Expected: logs remain visible through disconnect.
- Validation: no nested vertical scroll conflict.

### STEP 19
- Files: `AndroidManifest.xml`.
- Action: insert exact five permission declarations.
- Expected: methodology permissions represented.
- Validation: XML parse and lint; no runtime permission flow.

### STEP 20
- Files: production diff.
- Action: run Checkpoint D build and inspect UI on emulator.
- Expected: Lab 1 and Lab 2 present on one scrollable screen.
- Validation: no sensor regression.

### STEP 21
- Files: tests.
- Action: run all unit tests.
- Expected: old and new tests pass.
- Validation: `./gradlew.bat test` success.

### STEP 22
- Files: project.
- Action: run `assembleDebug`.
- Expected: debug APK builds.
- Validation: BUILD SUCCESSFUL.

### STEP 23
- Files: project.
- Action: run lint.
- Expected: no fatal lint findings.
- Validation: BUILD SUCCESSFUL; inspect report if not.

### STEP 24
- Files: Git diff.
- Action: inspect `git diff --check`, `git diff --stat`, and full diff.
- Expected: only scoped Lab 2 files and dependency/manifest/UI wiring.
- Validation: frozen files untouched.

### STEP 25
- Files: emulator.
- Action: complete all manual steps in section 19.
- Expected: functional evidence and screenshots.
- Validation: user, not agent, confirms acceptance.

### STEP 26
- Files: Git.
- Action: stop. Do not commit/push/tag automatically.
- Expected: validated working tree awaits user approval.
- Validation: report changed files and test commands.

## 17. Intermediate build checkpoints

### CHECKPOINT A — domain/contract/scanner

- Run: `./gradlew.bat :app:compileDebugKotlin`
- Run scanner unit test.
- Require: compilation and deterministic two-second virtual-time test pass.

### CHECKPOINT B — connector

- Run connector unit tests.
- Require: every state transition, repeated scan, cancellation, and logging test passes.

### CHECKPOINT C — ViewModel

- Run BLE ViewModel tests and `:app:compileDebugKotlin`.
- Require: facade delegates correctly and SensorViewModel is unchanged.

### CHECKPOINT D — UI/Manifest

- Run `./gradlew.bat assembleDebug`.
- Launch emulator for a short smoke test.
- Require: both Lab 1 and BLE section render; no permission dialog blocks mock.

### CHECKPOINT E — final

- Run `./gradlew.bat assembleDebug`
- Run `./gradlew.bat test`
- Run `./gradlew.bat lint`
- Require: all report BUILD SUCCESSFUL.

## 18. Final automated validation

From repository root in PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
git diff --check
git status --short
```

Expected: all Gradle commands return exit code 0 and `BUILD SUCCESSFUL`. Unit test count must increase by at least the new scanner/connector tests; do not assert an invented exact total. `git diff --check` must print nothing.

## 19. Manual emulator validation

### MANUAL STEP 01
Launch the configured Android emulator and open SleepTrackerSimulator.

### MANUAL STEP 02
Confirm title, educational disclaimer, and existing sensor section appear. Scroll to the appended `Mock BLE connection` section.

### MANUAL STEP 03
Confirm initial BLE status is Disconnected, Search is enabled, no device card exists, and terminal is empty.

### MANUAL STEP 04
Tap `Search for devices`. Immediately confirm Scanning status and progress indicator.

### MANUAL STEP 05
Before two seconds elapse, confirm no device is shown. At approximately two seconds, confirm exactly one `Sleep Tracker Hub` with `MOCK:00:11:22:33:44`.

### MANUAL STEP 06
Tap Connect. Confirm a visible Connecting state, then Connected after about 750 ms.

### MANUAL STEP 07
Confirm terminal shows SCAN, FOUND, CONNECTING, CONNECTED, and `STATUS:READY` notification entries.

### MANUAL STEP 08
Enter `STATUS_REQUEST`, tap Send, and confirm TX plus `ACK:STATUS_REQUEST` RX/notification lines.

### MANUAL STEP 09
Open Android Studio Logcat, filter `MockBleConnector`, and confirm the same successful packet exchange.

### MANUAL STEP 10
Tap Disconnect. Confirm status Disconnected, command controls disappear, logs remain, and Search becomes enabled.

### MANUAL STEP 11
Repeat Search. Confirm one device appears after two seconds and no duplicate devices are accumulated.

### MANUAL STEP 12
While scanning and connecting, open Emulator Extended controls → Virtual Sensors → Device Pose and change pose. Confirm X/Y/Z, magnitude, bubble, and filtered motion continue updating.

### MANUAL STEP 13
Hold pose stable until STILL appears. Make an abrupt pose change until MOVEMENT appears, then hold still until it returns to STILL.

### MANUAL STEP 14
Rotate the emulator between portrait and landscape. Scroll through all content; confirm no crash, overlap, or inaccessible controls.

### MANUAL STEP 15
Background and foreground the app. Confirm sensor updates resume and BLE UI does not spawn duplicate operations.

## 20. Screenshots for the Lab 2 report

### SCREENSHOT 1 — Scanning
- Must show: existing app identity/sensor context where possible, BLE Scanning status, progress indicator.
- Why: proves scan UI and asynchronous progress.

### SCREENSHOT 2 — Device discovered
- Must show: `Sleep Tracker Hub`, mock address, Connect button.
- Why: satisfies found-device list requirement.

### SCREENSHOT 3 — Connected and terminal
- Must show: Connected status, command control, terminal with `STATUS:READY`.
- Why: proves connection and notification/read semantics.

### SCREENSHOT 4 — Successful data transfer
- Must show: terminal TX and ACK/RX for `STATUS_REQUEST`.
- Why: proves Write and simulated response.

### SCREENSHOT 5 — Logcat
- Must show: filter/tag and successful TX/RX entries.
- Why: methodology explicitly requests Logcat evidence.

### SCREENSHOT 6 — Lab 1 plus Lab 2 coexistence
- Must show: sensor values/state and BLE section in one application, using scroll/composite captures if needed.
- Why: proves extension rather than replacement.

Do not add an initial/disconnected screenshot unless useful for narration; it is not stronger evidence than the required operational states.

## 21. Report material notes

- Protocol table: the three UUIDs, status Read/Notify, command Write.
- Code listings: `IBleConnector`, `MockBleScanner.scan`, core `MockBleConnector` transitions, and `BleViewModel` actions.
- State diagram: Idle/Disconnected → Scanning → DeviceFound → Connecting → Connected → Data Transfer; Connected → Disconnected; failures → Error.
- Explain asynchronous delay without blocking the main thread.
- Explain cancellation prevents stale scans/connections from overwriting current state.
- Explain StateFlow provides a current observable state and Compose recomposition.
- Explain real permissions versus mock technical needs.
- Explain stability principles: explicit states, cancellation, idempotent actions, bounded logging, and deterministic retry/re-scan.
- Map screenshots 1–2 to scanning/discovery, 3 to connection/read/notify, 4–5 to write/logging, 6 to Lab 1 preservation.
- Do not claim actual radio communication; state clearly that it is a Bluetooth interface-layer simulation.

## 22. Common failure modes

| Problem | Prevention |
|---|---|
| Replacing SensorScreen | Append BLE section; preserve all existing Lab 1 composables/content. |
| Merging BLE into SensorViewModel | Use separate BleViewModel. |
| Real Bluetooth calls | Keep mock independent of BluetoothAdapter/GATT/scanner. |
| Runtime permission dialog blocks demo | Declare permissions but do not request them for mock. |
| Blocking main thread | Use coroutine `delay`, never sleep. |
| Multiple scan jobs | Maintain one operation job and ignore double scan while Scanning. |
| Stale connection completes after disconnect | Cancel connection job before setting Disconnected. |
| Duplicate devices | Replace state list with scanner result; do not append. |
| Nondeterministic tests | Inject scope/delays and use coroutines-test virtual time. |
| Unbounded log memory | Keep last 50 entries. |
| Catching cancellation as an error | Rethrow `CancellationException`. |
| Connector created during recomposition | Instantiate through Activity ViewModel factory. |
| BLE unavailable when sensor unavailable | Place BLE section outside sensor-state branch. |
| Landscape clipping | Retain outer vertical scroll; avoid fixed content heights. |
| Breaking thresholds | Do not edit MovementClassifier. |
| Starting Lab 3 | Enforce strict scope boundary below. |
| Premature Git publication | Wait for manual validation and explicit approval. |

## 23. Definition of done

- [ ] Actual repository was rechecked before edits.
- [ ] Lab 1 frozen files/values remain unchanged.
- [ ] Service, status, and command UUIDs exist and are documented.
- [ ] `IBleConnector` abstraction exists.
- [ ] `MockBleScanner` discovers one fixed device at approximately 2 seconds.
- [ ] Scan button and progress indicator work.
- [ ] Discovered device list shows name/address.
- [ ] Connecting and Connected states are visible.
- [ ] Disconnect works and cancels pending connection.
- [ ] Data can be sent only while connected.
- [ ] TX and deterministic ACK/RX appear on screen and in Logcat.
- [ ] StateFlow drives Compose through lifecycle-aware collection.
- [ ] Repeat scan does not duplicate devices or jobs.
- [ ] BLE permission declarations match section 12.
- [ ] No unnecessary production dependency was added.
- [ ] New scanner/connector/ViewModel tests pass.
- [ ] Existing Lab 1 tests pass.
- [ ] `assembleDebug`, `test`, and `lint` succeed.
- [ ] Manual scan/connect/send/disconnect/re-scan succeeds.
- [ ] Virtual Sensors still drive X/Y/Z, magnitude, indicator, STILL/MOVEMENT.
- [ ] Rotation and background/foreground checks pass.
- [ ] Required screenshots are captured.
- [ ] Full diff has been reviewed.
- [ ] No commit, push, or tag occurs before explicit user approval.

## 24. Git instructions for later, not automatic execution

After the user completes and confirms manual validation:

```powershell
git diff
git status
git add <only reviewed Lab 2 files>
git commit -m "Lab 2: mock BLE communication"
git push origin main
git tag -a lab2 -m "Lab 2 checkpoint"
git push origin lab2
```

Never force-push. Never move, delete, or overwrite `lab1`. Do not include `LAB2_HANDOFF.md`; it is locally excluded. Do not commit until the user explicitly approves after manual validation.

## 25. Strict scope boundary

Forbidden in Lab 2:

- Firebase or any cloud SDK
- REST/cloud synchronization
- Room or any local database
- actuator control
- `VibratorManager`
- `CameraManager`
- TensorFlow Lite or ML inference
- Airflow
- background cloud pipelines
- authentication/accounts
- navigation architecture added solely for future labs
- repository/use-case/DI layers with no Lab 2 need
- any Lab 3, 4, 5, 6, or 7 functionality

ONLY LAB 2.

# EXECUTION PROMPT FOR GEMINI ANTIGRAVITY

Read `LAB2_HANDOFF.md` completely before changing any file. Then inspect the actual repository and verify every handoff assumption against the current code, Gradle configuration, manifest, tests, Git branch/status, and `lab1` tag. If actual code differs, follow repository evidence and the laboratory methodology while preserving the intent and report the discrepancy.

Implement Lab 2 by following `## 16. Exact implementation sequence for Antigravity` from top to bottom. Use the single target architecture specified here: separate `BleViewModel`, `IBleConnector`, deterministic `MockBleScanner`/`MockBleConnector`, StateFlow, and an appended BLE section inside the existing screen. Do not invent an alternative architecture.

Treat `## LAB 1 FROZEN FUNCTIONAL BASELINE` as immutable. Do not remove, replace, simplify, or retune the accelerometer pipeline, X/Y/Z, magnitude, indicator, classifier, smoothing factor 0.25, movement threshold 0.45 m/s², still threshold 0.20 m/s², hysteresis, lifecycle registration, Virtual Sensors behavior, or existing UI states.

Implement only Lab 2. Do not add any Lab 3+ functionality, cloud/database code, actuators, camera/vibration, ML/TFLite, or speculative layers. Use mock BLE only; do not use actual Bluetooth hardware APIs.

Run every intermediate checkpoint. Add the specified deterministic unit tests using coroutine virtual time. After implementation, run:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
git diff --check
```

All Gradle commands must succeed. Inspect the final diff and confirm frozen Lab 1 files/functionality were preserved. Do not commit, push, or create/move tags. Stop with the validated working tree and provide: changed-file summary, automated validation results, any deviations from this handoff with evidence, and the exact manual emulator validation steps from this document for the user to perform.
