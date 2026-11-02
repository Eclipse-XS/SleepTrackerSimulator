# Hardware Actuator Safety, Arbitration, and Lifecycle Audit

> [!CAUTION]
> Direct manipulation of smartphone actuators (such as the camera flash torch and eccentric rotating mass / linear resonant actuator vibrators) carries physical risks: excessive battery drain, thermal throttling, LED emitter degradation, and sleep disruption. All actuator invocations must be arbitrated with fail-safe boundaries.

---

## 1. Safety Architecture Overview

Hardware actuator execution is encapsulated within `AndroidSmartActuator`, which implements the `SmartActuator` domain interface:

```
┌────────────────────────────────────────────────────────┐
│             SmartScenario / Automation Rule             │
└───────────────────────────┬────────────────────────────┘
                            │ evaluatePrediction()
┌───────────────────────────▼────────────────────────────┐
│           BackgroundAutomationCoordinator               │
└───────────────────────────┬────────────────────────────┘
                            │ triggerAction()
┌───────────────────────────▼────────────────────────────┐
│                AndroidSmartActuator                    │
│   ┌────────────────────────────────────────────────┐   │
│   │ • Mutex Synchronization                        │   │
│   │ • Idempotency Checks                           │   │
│   │ • 30s Persistent Vibration Auto-Cutoff Job     │   │
│   │ • CameraManager Torch Availability Listener    │   │
│   └────────────────────────────────────────────────┘   │
└─────────────┬────────────────────────────┬─────────────┘
              │                            │
              ▼                            ▼
   ┌───────────────────────┐   ┌───────────────────────┐
   │ Camera2 Torch Flash   │   │ Android OS Vibrator   │
   │ (Single-shot / Off)   │   │ (Bounded 30s Limit)   │
   └───────────────────────┘   └───────────────────────┘
```

---

## 2. Implemented Fail-Safe Controls

### 1. Bounded Persistent Vibration Limit
- **Defect Addressed**: Persistent vibration rules previously had no upper execution ceiling, risking runaway vibration if the device was left unattended.
- **Implemented Fix**:
  ```kotlin
  const val MAX_PERSISTENT_VIBRATION_MS = 30_000L // 30 seconds max
  ```
  When `setPersistentVibration(true)` is invoked:
  - An asynchronous coroutine safety cutoff is scheduled:
    ```kotlin
    persistentVibrationCutoffJob?.cancel()
    persistentVibrationCutoffJob = scope.launch {
        delay(MAX_PERSISTENT_VIBRATION_MS)
        setPersistentVibration(false)
    }
    ```
  - Subsequent invocations of `setPersistentVibration(true)` while already vibrating are strictly idempotent and do not reset or prolong the safety timer.

### 2. Camera2 Torch Thermal Protection
- Camera torch toggles use `CameraManager.setTorchMode(cameraId, enabled)`.
- Checked for camera availability; if another application or Android system service reserves the camera sensor, errors are safely trapped and logged rather than crashing the recording process.
- Torch activations are bounded and immediately extinguished upon `stopAll()`.

### 3. Foreground Service Lifecycle Synchronization
- In `RecordingService.stopSources()`:
  - Called unconditionally on session stop, session pause, or unrecoverable sensor failure.
  - Explicitly executes `smartActuator.stopAll()`.
  - Ensures that no torch light or vibration motor can outlive the active recording foreground service.

---

## 3. Automated & Physical Verification Status

| Capability | Automated Test Status | Physical Observation Status | Verification Method |
|:---|:---|:---|:---|
| Persistent Vibration Idempotency | `PASSED` | `UNVERIFIED_BY_HARDWARE` | `SmartActuatorSafetyTest` |
| 30s Auto-Cutoff Safety Job | `PASSED` | `UNVERIFIED_BY_HARDWARE` | Coroutine delay virtual advance in unit tests |
| Session Stop Auto-Extinguish | `PASSED` | `UNVERIFIED_BY_HARDWARE` | `RecordingService` integration test |
| Physical Flashlight Illumination | N/A | `UNVERIFIED_BY_HARDWARE` | Requires human eye inspection of rear camera LED |
| Physical Haptic Motor Pulse | N/A | `UNVERIFIED_BY_HARDWARE` | Requires physical hand contact with test smartphone |

---

## 4. Physical Testing Procedure for Actuator Acceptance

1. **Vibration Auto-Cutoff Test**:
   - Enable Smart Rule: *"Vibrate continuously on sustained movement"*.
   - Trigger rule and release device.
   - Using a physical stopwatch, verify that the physical vibration motor halts automatically at exactly 30 seconds.
2. **Torch Interruption Test**:
   - Trigger rule activating camera torch.
   - Tap **"Pause Session"** in SleepSense.
   - Observe rear LED: the flashlight must extinguish immediately upon pause transition.
