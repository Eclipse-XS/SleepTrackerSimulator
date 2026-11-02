# LOCAL BASELINE AUDIT — SleepSense 2.0 & VirtualSleepBand

**Audit Date**: 2026-11-02
**Auditor**: Antigravity Principal Engineering & QA Auditor
**Local Workspace**: `C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator`
**Remote Origin**: `https://github.com/Eclipse-XS/SleepTrackerSimulator`
**Baseline Git Commit**: `00e43108bf00c637ae267cd67a3d1e902ced7341`
**Baseline Git Branch**: `feature/sleepsense-2.0-overhaul`

---

## 1. Environment & Toolchain Inventory

| Component | Detected Specification / Version | Source / Location |
|---|---|---|
| **OS** | Windows 10/11 Pro (x86_64, PowerShell 5.1 / 7) | Host environment |
| **Android SDK** | API 31–37, Platform-Tools, Emulator `Pixel_10_Pro` available | `C:\Users\Eclipse\AppData\Local\Android\Sdk` |
| **Java JDK (Active Studio)** | OpenJDK 25.0.3+-15898627-b508.16 (Android Studio JBR) | `C:\Program Files\Android\Android Studio\jbr` |
| **Java JDK (Secondary)** | JDK 17.0.12 LTS / Temurin 11.0.30 | `C:\Program Files\Java\jdk-17` |
| **Gradle Wrapper** | Gradle 9.6.0 (bin distribution, SHA-256 verified) | `gradle/wrapper/gradle-wrapper.properties` |
| **Android Gradle Plugin** | AGP 9.4.1 | `gradle/libs.versions.toml` |
| **Kotlin Version** | 2.3.21 | `gradle/libs.versions.toml` |
| **Compose BOM** | 2026.09.00 | `gradle/libs.versions.toml` |
| **Room Database** | Version 2.8.5, Schema Version 7, KSP 2.3.12 | `SleepTrackerDatabase.kt`, `app/schemas` |
| **TensorFlow Lite** | TFLite 2.16.1 (Android AAR), TensorFlow CPU 2.20.0 (Python) | `app/build.gradle.kts`, `ml/.venv` |
| **Python Environment** | Python 3.11.9, Polars 2.1.0, DuckDB 2.0.0, PyArrow 25.0.1, Pandera 0.35.0, Scikit-learn 1.9.1 | `ml/.venv` |
| **Node / Firebase** | Node.js available, `firebase-tests/package.json` for database rules testing | `firebase-tests/` |

---

## 2. Application Modules & Package Separation

1. **`app` module**:
   - Application Name: `SleepSense 2.0`
   - Application ID: `com.example.sleeptrackersimulator`
   - Target SDK: 37, Min SDK: 31, Compile SDK: 37 (`release(37)`)
   - Version Code: `2000001`, Version Name: `2.0-RC1`
   - Role: Main user application (Central BLE, sensor collection, Room persistence, on-device ML inference, background automations).

2. **`virtualsleepband` module**:
   - Application Name: `VirtualSleepBand`
   - Application ID: `com.example.virtualsleepband`
   - Target SDK: 37, Min SDK: 26, Compile SDK: 37
   - Version Code: `2000001`, Version Name: `2.0-RC1`
   - Role: Autonomous BLE wearable peripheral simulator (GATT server, Heart Rate Service `0x180D`, Battery Service `0x180F`, custom command characteristic).

The two modules produce completely distinct packages and can be installed side-by-side on the same or separate Android devices.

---

## 3. Pre-Change Test & Build Baseline

- **Android Unit Tests (`:app:testDebugUnitTest`)**: 243 tests executed and passing.
- **Companion Unit Tests (`:virtualsleepband:testDebugUnitTest`)**: 7 tests executed and passing.
- **Total Android Unit Tests**: 250 tests passed (0 failures, 0 errors).
- **Python ML & Data Pipeline Tests (`pytest`)**: 19 tests executed and passing (0 failures).
- **Existing Pre-built APKs**: Found in `FINAL_APK_RELEASE/`:
  - `SleepSense-2.0.apk`: 90,067,717 bytes, debug signed, SHA-256 `C4E6D455D295274D84798C415ED21549C087986FC67F90AB513DEAC333B7205A`
  - `VirtualSleepBand.apk`: 18,537,811 bytes, debug signed, SHA-256 `A72501B865EC960834A272B27CF55A45F536139C5A6C476BD267CD10FB9A279B`
- **Physical Hardware**: No physical Android phone or physical BLE peripheral currently attached via ADB. Android emulator AVD `Pixel_10_Pro` (API 36/37) is available locally.

---

## 4. Current ML Asset Inventory

| Asset Name | Path | Size | SHA-256 |
|---|---|---|---|
| `activity_classifier.tflite` | `app/src/main/assets/activity_classifier.tflite` | 11,368 bytes | `1CF844F774355E54F820C4860304DC43E42A4015F57C7097CEE9D16227858311` |
| `multimodal_classifier.tflite` | `app/src/main/assets/multimodal_classifier.tflite` | 12,960 bytes | `39E882DCC35AA1B0420AE3D6D59F83631058DC37D13C0FEF80AB1E858E62C158` |
| `activity_labels.txt` | `app/src/main/assets/activity_labels.txt` | 23 bytes | Labels: `REST`, `RESTLESS`, `ACTIVE` |

---

## 5. Comprehensive 28-Issue Baseline Defect Register

| ID | Issue Description | Baseline Classification | Baseline Root Cause / Code State |
|---|---|---|---|
| **01** | Connected BLE data may not enter the actual recording session | `CONFIRMED_BUG` | `TodayScreen` lacks SourceMode selector; `TodayViewModel.selectedSourceMode` defaults to `PHONE_ONLY`; `RecordingService` only subscribes to BLE if `activeMode.requiresBleConnection`. Even with BLE connected, recording runs as phone-only and persists no HR. |
| **02** | BLE heart rate must actually affect multimodal ML | `PARTIALLY_FIXED` | Multimodal model exists and is wired in `ModelSelectionEngine`, but blocked by Issue 01 from receiving real session data. Needs end-to-end integration and Android parity test. |
| **03** | Background recording & automations independent of UI | `FIXED_AND_VERIFIED` | Implemented in `BackgroundAutomationCoordinator` within service/session lifecycle; `SmartScenariosViewModel` is purely a UI observer. |
| **04** | Hardware actuator safety, arbitration, and lifecycle | `PARTIALLY_FIXED` | Emergency OFF exists, but persistent vibration waveform lacks a bounded safety timeout cutoff, and recording pause/stop doesn't cancel active actuators. |
| **05** | Long-running recording reliability | `PARTIALLY_FIXED` | Ingestion is bounded and copies values synchronously; process death sets `INTERRUPTED`. However, `observationDurationMs` does not subtract paused intervals, distorting quality metrics. |
| **06** | Incorrect simulation time at 250/500 ms in VirtualGattServer | `CONFIRMED_BUG` | `VirtualGattServer.kt` line 374: `simulationSeconds += (packetIntervalMs / 1000L).coerceAtLeast(1L)` forces at least 1s advance per packet, accelerating simulation by 4x at 250ms and 2x at 500ms. |
| **07** | BLE command acknowledgement & delivery semantics | `CONFIRMED_BUG` | `AndroidGattDeviceTransport.executeCommand` returns success on `gatt.writeCharacteristic()` without waiting for `onCharacteristicWrite` callback. |
| **08** | BLE reconnect and stale-data handling | `CONFIRMED_BUG` | No auto-reconnect or backoff logic in `AndroidGattDeviceTransport` when unexpected disconnect occurs; stale HR not cleared. |
| **09** | VirtualSleepBand command processing | `CONFIRMED_BUG` | `VirtualGattServer.kt` onCharacteristicWriteRequest accepts any characteristic write, does not validate UUID/command, and executes no physical/simulated vibration. |
| **10** | BLE simulation authenticity and source provenance | `FIXED_AND_VERIFIED` | Tracked via manufacturer specific marker `0xFFFF`/`SSV1`; mapped to `SourceKind.SIMULATED`. Baseline comparison rejects non-real data. |
| **11** | Empty recordings must not receive perfect coverage | `CONFIRMED_BUG` | `SessionCoordinatorImpl.kt` lines 365-371 defaults `windowCoverage = 1.0f` when windows are empty and `ingestionCoverage = 1.0f` when samples are 0. Empty sessions receive 100% coverage and EXCELLENT quality. |
| **12** | Average heart rate and summary consistency | `CONFIRMED_BUG` | `SessionCoordinatorImpl.kt` computes `meanHr` for cloud payload, but fails to pass `averageHeartRateBpm` to `SessionSummary`, leaving it always `null` in Room. |
| **13** | Local retention must actually execute | `FIXED_AND_VERIFIED` | `DataRetentionPolicy` and `DataRetentionWorker` scheduled every 24h via WorkManager. Deletes samples >7d, windows/preds >30d, protects active sessions. |
| **14** | Offline outbox integrity and recovery | `PARTIALLY_FIXED` | Room-backed `CloudOutboxDao` with atomic session save. Needs verification of purge on session deletion and multi-session backlog. |
| **15** | Explicit Firebase opt-in and consent | `CONFIRMED_BUG` | SettingsScreen shows "BUILD CONFIG" text with no persistent user toggle. `SessionCoordinatorImpl` enqueues cloud outbox operations without checking user consent. |
| **16** | Cloud deletion and retention consistency | `FIXED_AND_VERIFIED` | `FirebaseCloudRepository.deleteSession` deletes both `sessions` and `sessionSummaries`. `applyRetention` cleans up both. |
| **17** | Room schema migrations and referential integrity | `FIXED_AND_VERIFIED` | Migrations 1 through 7 defined in `SleepTrackerDatabase.kt`, exported schemas 1-7 in `app/schemas`, instrumented tests in `androidTest`. |
| **18** | Synthetic-only evaluation does not establish real accuracy | `FIXED_AND_VERIFIED` | Explicitly marked `REAL_WORLD_VALIDATION_PENDING` in Model Card; no false clinical claims made. |
| **19** | Proper dataset splitting and leakage prevention | `FIXED_AND_VERIFIED` | Group-aware splitting by session ID prevents window leakage between train/val/test splits. |
| **20** | Honest evaluation and model comparison | `FIXED_AND_VERIFIED` | Ablation study in `docs/ml/multimodal_ablation_study.json` compares heuristics, single-branch, and multimodal fusion. |
| **21** | Input contracts, sensor placement, and model selection | `PARTIALLY_FIXED` | Model card text discrepancy on HR normalization formula (`bpm/100` vs `(bpm-60)/40`); golden vector verification test needed in unit tests. |
| **22** | Accurate latency, energy, and model metadata | `PARTIALLY_FIXED` | Latency measured on CPU; model SHA256 hashes must be documented in model card; battery claims marked unverified without hardware monitor. |
| **23** | Verify the real end-to-end Python data pipeline | `FIXED_AND_VERIFIED` | Tested with `pytest` (19 passing tests); includes Polars cleaning, Pandera schemas, DuckDB analytics, Parquet export. |
| **24** | The public GitHub code is outdated | `DESIGN_LIMITATION` | Local branch `feature/sleepsense-2.0-overhaul` contains 4 unpublished commits ahead of `e2e1c11`. Publication plan required. |
| **25** | Production signing configuration | `DESIGN_LIMITATION` | Debug keystore used for local builds; production signing documented with secure injection architecture. |
| **26** | Release and Play Store build pipeline | `PARTIALLY_FIXED` | TargetSdk 37, compileSdk 37. Release minification configuration and build validation needed. |
| **27** | Privacy, foreground-service, and health-app compliance | `PARTIALLY_FIXED` | `FOREGROUND_SERVICE_TYPE_HEALTH` declared; privacy consent requires Issue 15 fix. |
| **28** | Product usefulness and trustworthy UX | `PARTIALLY_FIXED` | UI needs clear SourceMode selection (Phone vs Combined), device connection status on Today screen, and accurate quality reporting. |

---

## 6. Baseline Verification Verdict

The repository is healthy and builds cleanly with Gradle and Python. There are **7 CONFIRMED_BUGs** that directly break core functionality:
1. **Issue 01**: BLE HR not entering recording session.
2. **Issue 06**: Simulation time accelerating under 250/500ms intervals.
3. **Issue 07**: BLE GATT write returning success without write callback acknowledgement.
4. **Issue 08**: Lack of reconnect state machine on BLE connection loss.
5. **Issue 09**: VirtualSleepBand silently ignoring command writes without validation or vibration.
6. **Issue 11**: Empty recording receiving 100% coverage and EXCELLENT quality.
7. **Issue 12**: Mean HR missing from local `SessionSummary` and Room database.
8. **Issue 15**: Cloud synchronization consent missing persistent user control in data layer.

Remediation will proceed according to the phased engineering plan.
