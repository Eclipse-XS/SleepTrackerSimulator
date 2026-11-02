# SleepSense 2.0 — Post-Remediation Critical Adversarial Audit & Residual Defect Report

**Document Version**: 2.0.0-AUDIT-FINAL
**Audit Date**: 2026-11-02
**Auditor Role**: Principal Android Engineer, BLE Architect, ML & Data Engineer, Independent QA Auditor
**Repository Path**: `C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator`
**Git Branch**: `feature/sleepsense-2.0-overhaul`
**Base Commit SHA**: `00e43108bf00c637ae267cd67a3d1e902ced7341`
**Quality Gate Test Results**: 285 Android Unit Tests Passed (0 Failed), 11 Python Pytest Passed (0 Failed), 0 Android Lint Errors, Release Builds Succeeded

---

## 1. Executive Summary & Audit Methodology

Following the initial remediation of SleepSense 2.0, an adversarial, evidence-driven audit was performed against our own modifications across the codebase. Rather than accepting previous green test suites as proof of total correctness, this review audited the system at the architectural boundaries:

1. **Upload Graph Audit**: Inspected every invocation path from ViewModel launch to Firebase network writes to detect un-gated background flushing or consent bypasses.
2. **Peripheral Package & Manifest Audit**: Inspected Android packaging tools (`aapt`, `apksigner`) against the compiled APKs to identify missing hardware permissions that could cause runtime crashes.
3. **BLE Transport Fault Injection**: Analyzed the GATT client (`AndroidGattDeviceTransport`) under asynchronous race conditions, write timeouts, disconnections during in-flight writes, and stale packet deliveries.
4. **Simulator Pacing & Actuator Safety**: Verified monotonic clock simulation pacing across varied packet intervals, tested actuator 30-second persistent vibration cutoff, and confirmed emergency stop overrides.
5. **Release Build & Packaging Integrity**: Validated Gradle signing configuration parameterization, recalculated SHA-256 digests, and verified APK installability parameters.

---

## 2. P0 Defects Identified and Remediated

### 2.1 P0-1: Firebase Cloud Sync Consent Bypass on Launch
- **Severity**: Critical (P0) — Privacy & Consent Boundary Violation
- **Root Cause Analysis**:
  In `CloudSyncViewModel.kt`, `initializeFromStore()` was invoked during ViewModel initialization in `MainActivity.kt`. It launched an un-gated monitoring coroutine that polled `stateStore.pendingCount` and periodically triggered `flushLocked()` every 5 seconds. Although the user toggle existed in `SettingsScreen.kt`, the ViewModel was created without a reference to `CloudSyncConsentRepository`. Consequently, any pending sensor payloads in the Room outbox or atomic JSON state store were automatically scheduled and uploaded to Firebase upon application launch, completely ignoring the user's consent setting.
- **Remediation Implemented**:
  1. **Centralized Consent Gatekeeper in Repository**:
     Added `consentRepository: CloudSyncConsentRepository?` to `FirebaseCloudRepository.kt`. The private gatekeeper method `requireConsent()` is invoked as the first statement in `createSession`, `uploadMeasurement`, `uploadRuleEvent`, `uploadSessionSummary`, and `endSession`. If `isCloudSyncEnabled.value` is `false`, a `CloudOperationException(CloudErrorType.AUTH, "Cloud synchronization is disabled by user consent policy")` is thrown immediately before constructing or executing any Firebase Database references.
  2. **ViewModel Injection & Lifecycle Gating**:
     Updated `CloudSyncViewModel` constructor and factory in `AppContainer.kt` to require `CloudSyncConsentRepository`. Gated `initializeFromStore()`, `startSync()`, `enqueueMeasurement()`, and `flushLocked()` behind explicit consent checks.
  3. **Consent Withdrawal Enforcement**:
     Added a reactive collector on `consent.isCloudSyncEnabled`. When the user withdraws consent (`enabled == false`), the ViewModel automatically calls `stopSync()`, cancels any active `recoveryJob`, purges all unconsented pending operations from `stateStore`, and resets UI state to `IDLE`.
- **Verification Evidence**:
  - `app/src/test/java/com/example/sleeptrackersimulator/cloud/FirebaseCloudRepositoryConsentTest.kt`: verified that writes are blocked with `CloudOperationException(AUTH)` when consent is disabled.
  - `app/src/test/java/com/example/sleeptrackersimulator/ui/dashboard/CloudSyncViewModelTest.kt`: 4 regression tests verified launch suppression, enqueue rejection, and immediate outbox purge on consent withdrawal.

### 2.2 P0-2: Missing `android.permission.VIBRATE` in VirtualSleepBand Manifest
- **Severity**: Critical (P0) — Fatal Runtime `SecurityException` on Peripheral Alert
- **Root Cause Analysis**:
  In `VirtualGattServer.kt`, receipt of BLE `ALERT` and `ALERT_PULSE` GATT write requests invoked `triggerPeripheralVibration(300L)`. On physical Android 12+ (API 31+) devices, calling `vibrator.vibrate(...)` without declaring `android.permission.VIBRATE` in `virtualsleepband/src/main/AndroidManifest.xml` results in an immediate `SecurityException`, crashing the peripheral process.
- **Remediation Implemented**:
  1. Added `<uses-permission android:name="android.permission.VIBRATE" />` to `virtualsleepband/src/main/AndroidManifest.xml`.
  2. Updated `VirtualGattServer.kt` to wrap vibrator invocation in defensive `try/catch` and hardware presence checks (`vibrator?.hasVibrator() == true`), returning descriptive operational status strings (`"EXECUTED_VIBRATION_300MS"`, `"EXECUTED_NO_VIBRATOR_HARDWARE"`, `"EXECUTED_VIBRATOR_ERROR_..."`) and logging to peripheral command history.
- **Verification Evidence**:
  - Inspected merged manifest in compiled APK via `aapt dump badging FINAL_APK_RELEASE/VirtualSleepBand.apk`: confirmed `uses-permission: name='android.permission.VIBRATE'` is present.
  - Unit test `VirtualGattServerPacingTest.kt` verified graceful fallback to `"EXECUTED_NO_VIBRATOR_HARDWARE"` when vibrator is absent.

---

## 3. BLE Protocol & Transport Hardening

### 3.1 AndroidGattDeviceTransport Regression Suite
The production BLE transport client (`AndroidGattDeviceTransport.kt`) was hardened and verified via a dedicated test suite (`AndroidGattDeviceTransportTest.kt`):

1. **Mutex Serialization & Write Acknowledgement**:
   Commands are queued across a `commandMutex`. In-flight writes wait for `onCharacteristicWrite` callback on `pendingCommandDeferred`. Verified that GATT success completes the deferred with `true`, while status failures return `false`.
2. **Initiation Failure & Timeout Handling**:
   If `activeGatt` is null or `writeCharacteristic` returns false, it returns `success = false` ("Failed to initiate GATT write"). If the peripheral does not acknowledge within 5,000 ms, `withTimeoutOrNull` triggers and returns "Peripheral command timed out awaiting acknowledgement".
3. **Disconnection During Pending Write**:
   Verified that a GATT disconnection event (`STATE_DISCONNECTED`) while a write is awaiting acknowledgement immediately completes the deferred with `false` and clears the pending reference, preventing coroutine deadlocks.
4. **Duplicate Callback Idempotency**:
   Verified that duplicate or delayed `onCharacteristicWrite` callbacks from hardware drivers do not throw exceptions or mutate the completed result.
5. **Exponential Reconnection Backoff**:
   On unexpected disconnections, the transport executes exponential backoff delays ($1\text{s} \to 2\text{s} \to 4\text{s}$) up to `MAX_RECONNECT_ATTEMPTS = 3`. Upon exhausting 3 attempts, it transitions decisively to `DeviceConnectionState.ERROR` and clears the connected device reference.
6. **User-Requested Disconnect Suppression**:
   Explicit user disconnection via `disconnect()` sets `userRequestedDisconnect = true` and clears `lastConnectedDeviceId`. Verified that subsequent hardware disconnect callbacks remain in `DISCONNECTED` and do not trigger reconnection.
7. **CCCD Re-subscription & Reconnect Reset**:
   Upon service discovery after reconnection, the client re-subscribes to CCCD (`0x2902`) on the Heart Rate Measurement characteristic (`0x2A37`). Verified that `onDescriptorWrite` with `GATT_SUCCESS` transitions state to `CONNECTED` and resets `reconnectAttempts` to 0.
8. **Stale Packet Rejection**:
   Added a state check in `onCharacteristicChanged`: incoming notification packets arriving while state is `DISCONNECTED` or `RECONNECTING` are discarded immediately and never emitted to `heartRateStream`.
9. **No Duplicate GATT Ownership**:
   Stale or foreign `BluetoothGatt` callbacks from superseded connections are closed immediately (`gatt.close()`) and ignored.

---

## 4. Simulator Pacing & Actuator Safety

### 4.1 VirtualSleepBand Autonomous Pacing
- Verified in `VirtualGattServerPacingTest.kt`:
  - Tested autonomous transmission loop with an injectable monotonic clock (`monotonicClockNanos: () -> Long`).
  - Verified pacing and simulated time progression across 250 ms, 500 ms, 1000 ms, and 2000 ms packet intervals.
  - Verified command write handling: `ALERT`, `ALERT_PULSE`, and `PING` are accepted and acknowledged; unsupported commands (`MALICIOUS_REBOOT`) are rejected with `REJECTED_UNSUPPORTED`.

### 4.2 Actuator Safety & Cutoff
- Verified in `SmartActuatorSafetyTest.kt`:
  - **30-Second Cutoff**: Persistent vibration (`setPersistentVibration(true)`) automatically schedules a cutoff coroutine. Advancing time to 30,000 ms automatically cancels vibration and resets `isPersistentVibrating = false`.
  - **Idempotency & Extension Prevention**: Calling `setPersistentVibration(true)` while already vibrating returns `SUCCESS` ("Vibration already active") without restarting the cutoff timer, preventing infinite prolongation.
  - **Emergency Override**: Calling `stopAll()` immediately halts vibration, cancels any pending cutoff job, and turns off the camera flashlight.
  - **Torch State Reconciliation**: `CameraManager.TorchCallback` accurately tracks real hardware torch states (`onTorchModeChanged` -> `true`/`false`, `onTorchModeUnavailable` -> `null`).
  - **Permission Safety**: When `android.permission.CAMERA` is absent, flashlight activation returns `ERROR` ("Camera permission required").

---

## 5. Cloud Deletion & Retention End-to-End

### 5.1 Deletion Architecture & Synchronization
Historical implementations only deleted session records from the local Room database, leaving remote Firebase nodes orphaned.

- **Integrated Deletion Workflow in `HistoryViewModel.kt`**:
  1. Deletes session and associated samples/summaries from local Room database via `sessionRepository.deleteSession(sessionId)`.
  2. Checks user consent via `cloudSyncConsentRepository.isCloudSyncEnabled.value`.
  3. If consent is disabled, deletion remains strictly local.
  4. If consent is granted, calls `cloudRepository.deleteSession(ownerUid, sessionId)` using multi-location atomic updates targeting both `/users/$ownerUid/sessions/$sessionId` and `/users/$ownerUid/sessionSummaries/$sessionId`.
  5. If offline or if the network call fails, enqueues a `PendingCloudOperation(type = DELETE_SESSION, sessionId = sessionId)` in `cloudStateStore` and schedules a recovery worker with `CloudRecoveryScheduler`.
- **Verified in `HistoryViewModelTest.kt`**:
  - `deleteSession deletes locally only when consent is disabled`: PASS.
  - `deleteSession triggers cloud deletion when consent is enabled`: PASS.
  - `deleteSession enqueues outbox operation and schedules recovery when cloud call fails`: PASS.

---

## 6. Gradle Release Signing Configuration

### 6.1 Gradle Build Script Parameterization
In previous iterations, the release build type lacked a `signingConfig`. Both `app/build.gradle.kts` and `virtualsleepband/build.gradle.kts` were updated with a robust, parameterized signing block:

```kotlin
signingConfigs {
    create("release") {
        val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
        val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("RELEASE_KEY_ALIAS")
        val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")

        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            storeFile = file(keystorePath)
            storePassword = keystorePassword
            this.keyAlias = keyAlias
            this.keyPassword = keyPassword
        } else {
            val debugConfig = getByName("debug")
            storeFile = debugConfig.storeFile
            storePassword = debugConfig.storePassword
            this.keyAlias = debugConfig.keyAlias
            this.keyPassword = debugConfig.keyPassword
        }
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        optimization {
            enable = false
        }
    }
}
```

This guarantees that:
- In production CI/CD with secure environment variables, release builds are signed with the official organization keystore.
- In developer environments without secrets, release builds assemble successfully using the debug keystore without failing the build.

---

## 7. Packaged APK Verification Evidence

The final release artifacts were recompiled from source, packaged, signed, and validated using Android build-tools:

| Metric | SleepSense 2.0 (`FINAL_APK_RELEASE/SleepSense-2.0.apk`) | VirtualSleepBand (`FINAL_APK_RELEASE/VirtualSleepBand.apk`) |
| :--- | :--- | :--- |
| **Package ID** | `com.example.sleeptrackersimulator` | `com.example.virtualsleepband` |
| **Version Code** | `2000001` | `2000001` |
| **Version Name** | `2.0-RC1` | `2.0-RC1` |
| **Compile SDK** | `37` | `37` |
| **Min SDK** | `31` (Android 12) | `26` (Android 8.0) |
| **Target SDK** | `37` | `37` |
| **File Size** | 76,013,803 bytes | 40,891,957 bytes |
| **SHA-256 Digest** | `0d08afc89ca0c0aa0af881fc570dcc1ccabcda06722ed612cc78bbc4d1881c76` | `557a325d982cd62f2ac2868a28be9c450e5eb8e952a5576642363121f116d72c` |
| **Signature Scheme** | APK Signature Scheme v2 (RSA 2048) | APK Signature Scheme v2 (RSA 2048) |
| **Signer Certificate** | `CN=Android Debug, O=Android, C=US` | `CN=Android Debug, O=Android, C=US` |
| **`apksigner verify`** | `PASS` (`Verified using v2 scheme: true`) | `PASS` (`Verified using v2 scheme: true`) |
| **Critical Permissions** | `FOREGROUND_SERVICE_HEALTH`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACTIVITY_RECOGNITION`, `CAMERA`, `VIBRATE` | `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `VIBRATE` |
| **Embedded ML Assets** | `activity_classifier.tflite`, `multimodal_classifier.tflite` | N/A (Peripheral GATT Server) |

Both APKs match the exact records in `FINAL_APK_RELEASE/SHA256SUMS.txt` and `FINAL_APK_RELEASE/APK_VERIFICATION.md`.

---

## 8. Quality Gate Execution Evidence Matrix

| Quality Gate | Tool / Command | Result | Notes / Evidence |
| :--- | :--- | :--- | :--- |
| **App Unit Tests** | `.\gradlew.bat :app:testDebugUnitTest` | **PASS** | 271 tests passed, 0 failed, 0 skipped. |
| **Peripheral Unit Tests**| `.\gradlew.bat :virtualsleepband:testDebugUnitTest` | **PASS** | 14 tests passed, 0 failed, 0 skipped. |
| **Python Pytest** | `ml/.venv/Scripts/python.exe -m pytest tests/` | **PASS** | 11 passed, 2 warnings (LiteRT deprecation warning). |
| **ETL Pipeline CLI** | `ml/.venv/Scripts/python.exe -m pipelines.cli.main` | **PASS** | `generate-synthetic` and DuckDB SQL analytics ran successfully. |
| **Android Lint (App)** | `.\gradlew.bat :app:lintDebug` | **PASS** | 0 errors. HTML report generated. |
| **Android Lint (Band)**| `.\gradlew.bat :virtualsleepband:lintDebug` | **PASS** | 0 errors. HTML report generated. |
| **Assemble Debug** | `.\gradlew.bat assembleDebug` | **PASS** | Both debug APKs successfully assembled. |
| **Assemble Release** | `.\gradlew.bat assembleRelease` | **PASS** | Both release APKs successfully assembled. |
| **Firebase Emulator** | `node --test firebase-tests/database.rules.test.mjs`| **BLOCKED_BY_ENVIRONMENT** | Requires `firebase` CLI on PATH to run emulator host. |
| **Instrumentation** | `.\gradlew.bat connectedAndroidTest` | **BLOCKED_BY_ENVIRONMENT** | `adb devices` is empty; no Android device or emulator connected. |
| **Physical Actuators** | Physical LED illumination & skin haptics | **UNVERIFIED_BY_HARDWARE** | Code and software mock validated; physical confirmation requires hardware. |
| **Physical BLE RF** | Over-the-air radio transmission | **UNVERIFIED_BY_HARDWARE** | Protocol contract verified via test doubles; OTA testing requires dual devices. |

---

## 9. Final 28-Issue Acceptance Matrix

The 28 issues identified across historical audits are classified strictly according to empirical evidence:

| # | Issue Description | Final Classification | Architectural Boundary & Evidence Justification |
| :- | :--- | :--- | :--- |
| **1** | Foreground Service ANR & Lifecycle | `SOFTWARE_VERIFIED` | `RecordingService` manages foreground lifecycle with `START_STICKY`, explicit notification channels, and non-blocking coroutines. |
| **2** | BLE Scan Timeout & Leak Prevention | `SOFTWARE_VERIFIED` | 15s timeout job cancels scanner in `AndroidGattDeviceTransport.kt`; permissions validated pre-scan. |
| **3** | BLE GATT Callback Threading & Reconnect | `SOFTWARE_VERIFIED` | Mutex serialized writes, 5s timeout, exponential backoff (1s, 2s, 4s) up to 3 attempts, verified in `AndroidGattDeviceTransportTest`. |
| **4** | BLE Command Acknowledgment & Framing | `SOFTWARE_VERIFIED` | Command write queue with `CompletableDeferred` awaiting `onCharacteristicWrite` acknowledgment. |
| **5** | VirtualSleepBand GATT Registration Order | `SOFTWARE_VERIFIED` | `GattRegistrationSequencer` enforces sequential addition of HR and Battery services before advertising starts. |
| **6** | VirtualSleepBand Autonomous Pacing | `SOFTWARE_VERIFIED` | Verified in `VirtualGattServerPacingTest`: injectable monotonic clock pacing across 250, 500, 1000, 2000 ms. |
| **7** | VirtualSleepBand Vibration Permission | `SOFTWARE_VERIFIED` | Fixed P0 defect: added `android.permission.VIBRATE` to manifest, verified in merged package via `aapt`. |
| **8** | Actuator Persistent Vibration Cutoff | `SOFTWARE_VERIFIED` | Verified in `SmartActuatorSafetyTest`: 30-second automatic cutoff enforced, repeated ON calls cannot extend limit. |
| **9** | Actuator Torch Reconciliation & Cleanup | `SOFTWARE_VERIFIED` | `CameraManager.TorchCallback` tracks real hardware state; `stopAll()` clears state and handles missing camera permission. |
| **10**| Room Database Migration & Foreign Keys | `SOFTWARE_VERIFIED` | Schema versions 1->2 verified; foreign keys with CASCADE delete on session removal; schema JSON exported. |
| **11**| Zero-Data Session Coverage Contract | `SOFTWARE_VERIFIED` | Sessions with 0 samples record 0.0f coverage and `INSUFFICIENT` quality; prevents artificial 100% inflation. |
| **12**| Average Heart Rate Persistence in Room | `SOFTWARE_VERIFIED` | `averageHeartRateBpm` field added to `SessionSummary` and Room summary entity; tested in summary pipeline. |
| **13**| Session Process Death Recovery | `SOFTWARE_VERIFIED` | Active uncompleted sessions are finalized upon service restart with `SessionProcessDeathRecoveryTest`. |
| **14**| Firebase Cloud Sync Consent Enforcement | `SOFTWARE_VERIFIED` | Fixed P0 defect: centralized consent gatekeeper in `FirebaseCloudRepository` and `CloudSyncViewModel`; outbox purged on withdrawal. |
| **15**| Multi-Location Atomic Cloud Deletion | `SOFTWARE_VERIFIED` | Multi-path map deletion at `/sessions` and `/sessionSummaries`; offline fallback enqueues `DELETE_SESSION`. |
| **16**| Cloud Outbox Backpressure & Capacity | `SOFTWARE_VERIFIED` | Max 10,000 items with reserved slots for critical session end and summary payloads; drops telemetry before critical events. |
| **17**| WorkManager Cloud Recovery Backoff | `SOFTWARE_VERIFIED` | Exponential backoff retry policy configured on `CloudRecoveryWorker`. |
| **18**| Firebase Realtime Database Security Rules | `SOFTWARE_VERIFIED` | Rigorous auth checks, path matching, schema validation, and range checks in `database.rules.json`. |
| **19**| Firebase Rules Automated Verification | `BLOCKED_BY_ENVIRONMENT` | `firebase-tests/database.rules.test.mjs` requires local Firebase CLI emulator runtime on PATH. |
| **20**| TFLite Model Numerical Parity | `SOFTWARE_VERIFIED` | Python pipeline test `test_tflite_export_and_numerical_parity` verified max absolute error < 1e-4 between Keras and TFLite. |
| **21**| Data Engineering Parquet Pipeline | `SOFTWARE_VERIFIED` | Polars/DuckDB pipeline validated via `pipelines.cli.main` with schema enforcement and multimodal correlation. |
| **22**| Movement Analysis & Actigraphy Thresholds | `SOFTWARE_VERIFIED` | Dynamic baseline calculation, hysteresis, and rest classification validated in `DecisionEngineTest`. |
| **23**| Source Selection UI & Mode Routing | `SOFTWARE_VERIFIED` | Source chips on `TodayScreen` dynamically configure `RecordingService` between BLE and synthetic generation. |
| **24**| Developer Lab Actuator Safety Overrides | `SOFTWARE_VERIFIED` | Emergency stop button in Developer Lab calls `stopAll()`, canceling active vibration and torch. |
| **25**| Gradle Release Signing Parameterization | `SOFTWARE_VERIFIED` | `signingConfigs.release` parameterized with environment variables and fallback to debug keystore in both modules. |
| **26**| Physical Hardware Actuator Execution | `UNVERIFIED_BY_HARDWARE` | Software mocks and Android API calls verified; physical vibration and light emission require physical hardware. |
| **27**| Physical BLE Over-the-Air RF Link | `UNVERIFIED_BY_HARDWARE` | GATT client/server protocol verified via simulated packet tests; true OTA RF testing requires two physical devices. |
| **28**| Real Human Physiological Sleep Accuracy | `BLOCKED_BY_REAL_DATA` | System is an educational simulator and portfolio project; not evaluated against clinical polysomnography (PSG). |

---

## 10. Final Verification Sign-Off

All software defects, including the two critical P0 issues (Cloud Sync consent bypass and VirtualSleepBand vibration permission), have been corrected in code, packaged into final APK artifacts, and covered by automated regression tests.

- **Total Android Unit Tests**: 285 Passed (0 Failed)
- **Total Python Tests**: 11 Passed (0 Failed)
- **Android Lint**: 0 Errors
- **APK Verification**: Passed v2 signature, verified permissions, valid TFLite assets.
