# Screen-recorder crash investigation

Application base commit: `aa0f59e9fc1e1ba0302d5ae3a47ea99f171e2549` plus the TFLite lifecycle fix in this acceptance change.

## Reproduction

On the Android 16 Pixel_10_Pro AVD, a real recording was started through the installed application. During recording, `adb shell screenrecord` was started. The application process terminated after approximately 49 seconds.

Observed crash evidence:

- native `SIGABRT` in `com.example.sleeptrackersimulator`;
- `Scudo ERROR: invalid chunk state when deallocating`;
- stack frames in `libtensorflowlite_jni.so` during `NativeInterpreterWrapper.run`;
- process was absent after the crash.

Root cause: the application-scoped `ActivityClassifier` was shared by the recording coordinator and `MlViewModel`, but TensorFlow Lite `Interpreter` is not thread-safe. Both consumers could call it concurrently. `MlViewModel.onCleared()` also closed the shared interpreter while the recording service could still use it.

## Fix and retest

- `classify()` and `close()` now share one monitor.
- close is idempotent and blocks until inference completes.
- inference after close is rejected before entering JNI.
- `MlViewModel` no longer closes an application-owned classifier.
- Added a two-thread, 200-inference instrumentation regression test.

Retest on the same AVD:

| Scenario | Actual outcome | Result |
|---|---|---|
| Recording with 30-second `adb screenrecord` | Process remained alive; no app crash; recording continued | PASS_EMULATOR |
| Continued recording after screenrecord (>70 seconds total) | Process alive; no TFLite/Scudo crash | PASS_EMULATOR |
| 20 Pause/Resume cycles | Recording remained active; no crash | PASS_EMULATOR |
| Background + screen off for 5 minutes | Process alive; no crash; session completed | PASS_EMULATOR |
| Persisted completion | UI showed `Data coverage 100%` and a completed summary | PASS_EMULATOR |
| Native vendor screen recorder on affected phone | Affected phone unavailable | NOT_REPRODUCED_ON_AFFECTED_DEVICE |
| 30-minute physical recording | No physical device | NOT_VERIFIED |

`adb shell screenrecord` is not treated as equivalent to a vendor screen recorder.

Evidence: [session summary](evidence/session-summary-after-endurance.png).
