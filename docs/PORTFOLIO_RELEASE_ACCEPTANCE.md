# SleepSense 2.0 — Portfolio Release Acceptance

Date: 2026-11-03. Scope: engineering prototype using synthetic data and movement-state classification. This is not a certified medical device or a validated REM/NREM sleep-stage detector.

## A. Repository snapshot

- Branch: `feature/sleepsense-2.0-overhaul`
- Starting SHA: `6cf59020ba4f87f92e4aeb29b8c6563fccc1c8c8`
- Final SHA: recorded by `git rev-parse HEAD` after the release-acceptance commit; the final response is authoritative because a commit cannot embed its own hash.
- Starting tree: clean. Final intended tree: clean after commit.
- Remote: `origin` (`https://github.com/Eclipse-XS/SleepTrackerSimulator.git`).

## B. Historical issue dispositions

| Issue | Current finding and correction | Evidence | Disposition |
|---|---|---|---|
| AND-001 | Sensor acquisition now starts before persistence enters `RECORDING`; failed start cannot create a ghost session. Resume also reacquires before state transition. | Android build; coordinator regressions | FIXED_AND_VERIFIED |
| AND-002 | Epoch-invalidated queued samples increment the drop counter. | Android unit/build verification | FIXED_AND_VERIFIED |
| AND-003 | Invalid samples increment loss accounting instead of disappearing silently. | Coordinator regression suite | FIXED_AND_VERIFIED |
| ARCH-001 | Existing bounded channel (512) and serialized ingestion remain; no unbounded buffer was found. Slow-path load was covered by existing JVM tests, not device profiling. | JVM suite | ALREADY_FIXED_AND_VERIFIED, device profiling NOT_VERIFIED |
| CLOUD-001 | Self-contained summary operations drain without an active monitoring session; non-deliverable session-dependent operations receive terminal failure. | `session summary drains after monitoring session has ended` | FIXED_AND_VERIFIED |
| CLOUD-002 | “Delete All Local Data” is strictly local, cancels stale uploads, preserves durable cloud-deletion tombstones, and no longer performs an unconfirmed remote-retention call. Cloud deletion remains independent of upload consent. | Cloud erasure policy tests; Android build | FIXED_AND_VERIFIED |
| PRIV-001 | Consent now names pseudonymous measurements, summaries, metadata, and automation events. Firebase UID isolation and schema rejection pass emulator tests. | Firebase emulator 11/11 | FIXED_AND_VERIFIED |
| MLOPS-001 | Promotion rejects legacy `[1,6]`; Android contract is exactly `[1,20,3]`. | Python promotion regression | FIXED_AND_VERIFIED |
| MLOPS-002 | Deployment manifest records asset hashes/contracts; runtime model IDs contain deployed SHA prefixes. Candidates remain explicitly unpromoted. | Python TFLite integrity/parity tests | FIXED_AND_VERIFIED |
| ML-002 | Normal full-population metrics are computed from `y_test_full`; shifted full population remains separately named. | Python suite/static mapping | FIXED_AND_VERIFIED |
| ML-003 | Manifest epochs now match the actual multimodal `fit(... epochs=14)`. | Python suite/static mapping | FIXED_AND_VERIFIED |
| ML-001 | Domain-shift limitations remain reported; no unsupported accuracy improvement was claimed. | README/model documentation | ALREADY_FIXED_AND_VERIFIED |
| ML-004 | `UNCERTAIN` is no longer converted to `REST` for automation; class-based automation is skipped. | Android unit/build verification | FIXED_AND_VERIFIED |
| BLE-001 | Existing transport-level status semantics and tests were retained. Physical actuator completion cannot be measured here. | JVM BLE tests; no hardware | ALREADY_FIXED_AND_VERIFIED; physical completion NOT_VERIFIED |
| DE-001 | SQL Gate 7 now detects within-session overlap with `LAG(end_utc_millis)`. | Mutation test | FIXED_AND_VERIFIED |
| DE-002 | No new bounded-memory claim was introduced; long-device-run measurement was unavailable. | Static inspection | NOT_VERIFIED |
| DE-003 | Existing storage safety tests pass; interrupted Parquet publication behavior is covered by the current storage suite. | Python data suite | ALREADY_FIXED_AND_VERIFIED |

An additional deterministic UI-state race was found during final verification: concurrent collectors could overwrite onboarding dismissal through non-atomic read-copy-write updates. `TodayViewModel` now uses atomic `MutableStateFlow.update`; the full 281-test app suite then passed.

## C. Acceptance gates

| Gate | Result | Evidence and limits |
|---|---|---|
| G1 Functional correctness | CONDITIONAL PASS | Both APKs build; JVM integration paths pass. Runtime launch, physical BLE, vibration, and emulator LR7 demonstration are NOT_VERIFIED because `adb devices` returned no device. |
| G2 Reliability/data integrity | PASS | Lifecycle, loss accounting, cloud recovery, deletion semantics, privacy rules, SQL mutation checks pass. |
| G3 ML correctness/reproducibility | PASS | Exact contracts, deployed hashes, TFLite execution/parity, cohort mapping, and uncertainty handling verified. |
| G4 Automated verification | CONDITIONAL PASS | Build, JVM tests, lint, Python suite, and Firebase rules pass. Instrumentation is BLOCKED_BY_ENVIRONMENT (no device/emulator). |
| G5 Portfolio readiness | PASS | README and existing architecture material retained; deployment manifest and this evidence report added; debug APKs produced. |

Verdict: **CONDITIONAL_GO**. The narrower engineering-prototype scope is credible and reproducible. Unconditional GO is prohibited because launch/instrumentation, physical BLE actuation, and a fresh on-device LR7 run were not executed.

## H. Product integration follow-up — 2026-11-03

The earlier emulator limitation no longer applies. A `Pixel_10_Pro` AVD (`sdk_gphone16k_x86_64`) was available with `getconf PAGE_SIZE = 16384`.

- Today now exposes only Phone Monitoring and Phone + Wearable. Combined mode is disabled until the production GATT transport has a heart-rate packet no older than 10 seconds. RecordingService enforces the same rule.
- Production inference is observable through `SessionCoordinator.liveInference` and shown on Today with class, LiteRT/fallback mode, confidence when meaningful, and collection state. The displayed result is the same prediction persisted to Room.
- Devices and Developer BLE Diagnostics observe the same production `DevicesUiState`; an open GATT connection without current notifications is explicitly shown as `NO CURRENT HR`.
- In-app simulation is labeled `In-app Simulator · No Bluetooth`. The standalone VirtualSleepBand advertises the same Heart Rate service, command characteristic, and `SSV1` manufacturer marker used by the production scanner.
- The deterministic 90-night demo now contains 48 aggregate feature windows and 48 predictions per night (4,320 timeline predictions total), without fabricated raw accelerometer samples. Demo provenance remains `DEMO`/`SIMULATED`, imports are idempotent, and reset targets only demo sessions.
- Developer Lab is presented as Developer & Diagnostics with five areas: BLE, ML, Demo Data, Session & Cloud, and Actuators. Academic LR1–LR7 tools remain reachable through Academic Lab View.

Verification evidence:

- Both debug APKs, both JVM suites, and both lint tasks: PASS.
- Android instrumentation: PASS, 35/35 app tests on the 16 KB AVD; VirtualSleepBand connected test task also PASS.
- Bundled LiteRT model: actual native initialization and inference PASS on the 16 KB AVD; six `ActivityClassifierInstrumentedTest` cases passed and runtime Logcat reported `libtensorflowlite_jni.so ... ok`.
- ELF verification: `libdatastore_shared_counter.so`, `libtensorflowlite_jni.so`, and `libandroidx.graphics.path.so` have every `PT_LOAD` aligned to `0x4000`.
- APK verification: Build Tools 36 `zipalign -c -P 16 -v 4` returned `Verification successful`.
- Python: 75 passed, 12 subtests passed. Firebase Realtime Database rules emulator: 11/11 passed.
- ADB product smoke test: launch, phone recording, live REST inference through accelerometer LiteRT, Pause, Resume, Finish, History persistence, Insights rendering, Devices, and Developer & Diagnostics all observed without an application fatal exception.
- Physical two-device BLE GATT and physical actuator execution remain `NOT_VERIFIED`; one emulator is not a substitute for a peripheral device.

Updated verdict: **CONDITIONAL_GO**. Core phone-only recording, native LiteRT inference, persistence, navigation, 16 KB runtime, and diagnostics are verified. Physical BLE remains hardware-blocked, so `GO` would overstate the evidence.

## D. ML integrity

See `ml/artifacts/deployment_manifest.json`. Embedded accelerometer and multimodal assets use float32 temporal windows, output `[REST, RESTLESS, ACTIVE]`, and have SHA-256-bound runtime IDs. The full Python suite executed real TFLite inference and numerical parity checks. Published performance remains synthetic-data evidence only; domain shift is material and no clinical inference is supported.

## E. Test matrix

| ID | Result | Method |
|---|---|---|
| T01–T03 | PASS | lifecycle ordering plus coordinator/JVM state-machine tests |
| T04–T06 | PASS | bounded channel/accounting implementation and JVM load regressions; device soak NOT_VERIFIED |
| T07–T11 | PASS | cloud outbox and erasure policy JVM tests |
| T12–T13 | PASS | Firebase Realtime Database emulator rules tests |
| T14–T16 | PASS | Python promoter, deployed inference/parity, and manifest mapping tests |
| T17 | PASS | automation path no longer accepts `UNCERTAIN` as `REST`; Android suite passes |
| T18 | PASS for transport failure semantics | JVM BLE tests; physical completion NOT_VERIFIED |
| T19 | PASS | SQL overlap mutation test |
| T20 | PASS | storage safety/data pipeline suite |
| T21 | PASS | tensor/window parity tests |
| T22 | NOT_VERIFIED | no Android device/emulator for fresh observable LR7 run |
| T23 | PASS | process-death/history JVM tests |
| T24 | PASS with instrumentation exception | final build/JVM/lint/Python/Firebase commands below |

## F. Executed verification

- `gradlew :app:assembleDebug :virtualsleepband:assembleDebug :app:testDebugUnitTest :virtualsleepband:testDebugUnitTest :app:lintDebug :virtualsleepband:lintDebug` — PASS.
- `uv run pytest -q` — PASS: 75 tests, 12 subtests; 43 non-failing deprecation warnings.
- `npx firebase-tools emulators:exec --only database "npm --prefix firebase-tests test"` with JDK 21 — PASS: 11/11.
- Android instrumentation/launch — BLOCKED_BY_ENVIRONMENT: Android SDK `adb devices` reported no connected target.
- CI for the final commit — NOT_VERIFIED locally; remote workflow status must not be inferred before push.

## G. Artifacts and remaining limitations

- `app/build/outputs/apk/debug/app-debug.apk` — 90,254,793 bytes, debug/internal-demo signing.
- `virtualsleepband/build/outputs/apk/debug/virtualsleepband-debug.apk` — 18,537,811 bytes, debug/internal-demo signing.
- No production signing credentials were supplied.
- No physical-device actuator completion, long overnight soak, or fresh emulator LR7 trace was executed.
- Synthetic domain shift remains a model limitation, not a hidden release defect.
