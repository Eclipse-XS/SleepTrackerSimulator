# Test matrix

| Check | Result | Evidence / limitation |
|---|---|---|
| Baseline `:app:testDebugUnitTest` | PASS | Fresh Gradle run. |
| Baseline `:app:assembleDebug` | PASS | Fresh Gradle run. |
| Baseline `:virtualsleepband:assembleDebug` | PASS | Fresh Gradle run. |
| `:virtualsleepband:testDebugUnitTest` | SKIPPED | `NO-SOURCE`; there are no companion unit tests. |
| Post-change `:app:testDebugUnitTest` | PASS | 197 tests, 0 failures, 0 errors, 0 skipped across 33 XML suites. |
| `:app:assembleDebug` / `assembleRelease` | PASS | Debug and unsigned release APKs produced. |
| `:app:lintDebug` / `lintVitalRelease` | PASS | Final combined Gradle run completed successfully. |
| Companion debug/release assembly and lint | PASS | Both APK variants and both lint gates completed successfully. |
| Instrumentation | SKIPPED | `adb` unavailable on PATH. |
| Screen recorder, lock, background, rotation, endurance | SKIPPED | Requires emulator/physical device automation. |
| Physical torch/vibration | SKIPPED | Requires supported hardware and human confirmation. |
| Two-phone BLE, 60 packets, reconnect | SKIPPED | Two physical devices unavailable. |

Historical XML and screenshots in `docs/evidence` are not counted as newly executed results.
