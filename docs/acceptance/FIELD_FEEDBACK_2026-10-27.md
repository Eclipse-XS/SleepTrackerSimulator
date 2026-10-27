# Field feedback hardening audit — 2026-10-27

Base: `feature/sleepsense-product-overhaul` at `0db7afaaf27d466085e54fbe49598fb2f5235ddf`.
This is a **partial remediation**, NOT a production release certificate. Physical-device regression and Gradle CI are pending.

## Confirmed code observations

1. `RecordingService.onSensorChanged()` read Android `SensorEvent.values` inside an asynchronously scheduled coroutine. Android reuses SensorEvent buffers. This patch snapshots x/y/z/timestamp/accuracy on the callback thread. **This is a signal-integrity defect; it has not been proven to cause the reported 5-second crash.**
2. `RecordingService` did not catch exceptions from asynchronous recording initialization or sensor ingestion. The patch adds diagnostic events, though device logcat is still required for root-cause analysis.
3. `ActuatorViewModel.applyFlashlight(false)` previously skipped the camera command when its UI state already said OFF. This could leave the physical torch ON after state drift. This patch forwards OFF even when the UI believes it is OFF and prevents stale Firebase ON commands from undoing an explicit manual OFF.
4. MainActivity's `onPause()` invokes `actuatorViewModel.setForegroundActive(false)`, which invokes `stopAll()`. Therefore the physical actuator *may* be intentionally deactivated when screen recording opens the system UI. This requires instrumented device verification.
5. The main application wires `MockBleConnector` and `MockDeviceTransport` in `DefaultAppContainer`. The Devices-screen demo toggle is **not** evidence of connecting to an external Android GATT peripheral. The real `AndroidGattDeviceTransport` exists but is not wired into that UI. **Physical two-device GATT E2E remains unverified.**
6. Independent `:virtualsleepband` application existed, but its heart-rate and battery values depended on UI sliders. The patch replaces streaming pulse generation with a bounded, deterministic, non-medical synthetic waveform and removes pulse/battery sliders.
7. Advertising payload included the device name and two service UUIDs; the patch advertises the heart-rate UUID without the name to reduce advertising overflow risk. Confirmation requires hardware.
8. `InsightsViewModel` gated personal insights to 7/30/90-day choices and at least 3 comparable sessions. `SessionSummary.isComparableWithBaseline` requires **real data, at least 85% coverage, at least 30 minutes**. The patch removes the 7/30/90 selector, queries the complete retained summary history, and shows stored/comparable counts, but does **not** bypass the scientific baseline quality gate.
9. The project ML report describes 98.53% accuracy on a synthetic holdout set of 750; this **must not** be marketed as 98% real-world sleep detection, nor as a diagnostic or sleep-stage classifier.

## Known blockers remaining

- Crash reported by a tester about five seconds into recording: **not reproduced or root-caused**. Capture `adb logcat -b crash -d`, Android version, device brand, exact app build, and distinguish Android screen recording from sleep-session recording.
- Torch and vibration: manual hardware acceptance on affected phone is pending. A successful API call does not prove a physical effect; respect hardware/DND/OS restrictions.
- Device UI uses mocks rather than the independent GATT companion: real scan/connect/CCCD/notifications/disconnect/reconnect path is pending.
- Virtual GATT measurements must be tagged `SIMULATED` end to end if integrated with production transport; current generic GATT transport defaults to `BLE_REAL`. Never mix simulated BPM into a personal baseline.
- No in-app Room demo-history importer is implemented by this patch. Separate synthetic CSV dataset can demonstrate analytics externally but must not be passed off as personal history.
- Insights does not automatically refresh every time the screen appears; refresh/lifecycle handling should be implemented before release.
- The `:virtualsleepband` simulator advances only while advertising and the Activity currently stops advertising in `onDestroy`; robust overnight companion usage needs a foreground service.
- ML requires independent subject-wise validation on consented real data, domain-shift evaluation, calibration, confusion matrices, confidence intervals, and battery/device benchmarking.
- Release gate: zero P0/P1 device failures, Android 14/15/16 permissions review, signed Play App Bundle, Play policy/privacy/data safety compliance, and privacy/retention verification.

## Minimal verification commands

```powershell
.\gradlew :app:testDebugUnitTest :virtualsleepband:testDebugUnitTest :app:assembleDebug :virtualsleepband:assembleDebug
adb logcat -c
# Reproduce start recording + system screen recorder on target physical device
adb logcat -b crash -d
adb logcat -d -s SleepTracker-Lifecycle SleepTracker-Actuators AndroidRuntime
```

Acceptance conditions:
- 30-min sensor session while screen recording: no force close, correct Room writes and summary.
- Torch OFF button turns off camera on hardware; repeated OFF calls remain safe, remote stale ON cannot restart it.
- Vibration visibly reports hardware unavailability or confirmed API result; record test evidence on physical devices.
- 2 phones, BLE advertiser, scanner, connection, CCCD notifications, ≥60 received simulated readings, disconnect/reconnect.
- `Insights`: 0 real sessions shows honest baseline notice; ≥3 eligible real sessions shows actual baseline metrics. Fictional records never qualify.
