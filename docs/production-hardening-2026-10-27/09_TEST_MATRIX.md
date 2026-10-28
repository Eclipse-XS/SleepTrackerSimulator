# Test matrix

Final local correctness gate executed on 2026-10-28 against the code staged for PR #3.

| Check | Result | Evidence / limitation |
|---|---|---|
| `:app:testDebugUnitTest` + `:virtualsleepband:testDebugUnitTest` | PASS | 223 tests, 0 failures, 0 errors, 0 skipped across generated JUnit XML. |
| `:app:connectedDebugAndroidTest` | PASS | 27 tests on Pixel 10 Pro AVD, Android API 16; includes Room corruption cases and Compose Insights UI. |
| App debug/release assembly | PASS | Both APK variants assembled locally. Release APK is not Play-signed. |
| VirtualSleepBand debug/release assembly | PASS | Both APK variants assembled locally. |
| App `lintDebug` / `lintVitalRelease` | PASS | Fresh local Gradle gate after correctness changes. |
| VirtualSleepBand `lintDebug` / `lintVitalRelease` | PASS | Fresh local Gradle gate; missing-permission cleanup path corrected. |
| Firebase Realtime Database rules | PASS | 11/11 tests using the local Firebase Database Emulator. |
| Python evaluation-quality tests | PASS | 8/8 tests; malformed probability vectors, labels, bins, empty inputs and leakage covered. |
| Dataset CSV verification | PASS | Schema, 1,200-row ingestion and synthetic benchmark pipeline completed. The synthetic score is not real-world accuracy evidence. |
| GitHub Actions | PASS | Android and `firebase-rules` jobs completed successfully for code commit `3c435e053724b3e944090c4601fcb2db0251fe3c` in [run 40686053610](https://github.com/Eclipse-XS/SleepTrackerSimulator/actions/runs/40686053610). |
| Physical torch/vibration | NOT_VERIFIED | No supported physical-device observation was performed. Android API success is not physical-effect evidence. |
| Two-phone BLE E2E | NOT_VERIFIED | No two-device scan/CCCD/stream/reconnect measurement was performed. |
| Original screen-recorder crash reproduction | NOT_VERIFIED | Emulator tests do not reproduce the original physical field condition. |

Historical XML, screenshots and earlier workflow runs are not counted as results for this gate.
