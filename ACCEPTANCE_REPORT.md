# Acceptance report

Environment: Windows host, Gradle 9.6, Android compile SDK 37, JVM/Python tests. No attached Android devices or physical BLE hardware were established during this run.

| ID | Expected | Actual | Result | Evidence |
|---|---|---|---|---|
| BUILD-01 | Main Kotlin compiles | `:app:compileDebugKotlin` succeeded | PASS | Gradle output |
| BUILD-02 | Band Kotlin compiles | `:virtualsleepband:compileDebugKotlin` succeeded | PASS | Gradle output |
| TEST-PY | Data/ML Python suite | 65 tests passed | PASS | pytest output |
| JVM-UNIT | Android-module unit suites | 300 tests, zero failures/errors/skips | PASS | `*/build/test-results/testDebugUnitTest` |
| WS-VALIDATE | valid/malformed/range validation | 3 protocol tests passed | PASS | `app/build/test-results/testDebugUnitTest/TEST-com.example.sleeptrackersimulator.data.ble.WearableProtocolTest.xml` |
| WS-E2E | independent APK HR crossing boundary | no two-emulator execution yet | BLOCKED | environment |
| BLE-E2E | physical GATT exchange | no two-device hardware | BLOCKED | environment |
| ML-REAL | real multimodal benefit | no suitable dataset | BLOCKED | model card |
| ROOM-MIGRATION | preserve v1-v7 data | existing instrumentation tests located; not executed without device | NOT_RUN | `app/src/androidTest/...Migration*` |
| ACT-PHYSICAL | flashlight/vibration execution | no physical device | BLOCKED | environment |

An unexecuted test is not classified as passing. Mocked BLE and synthetic HR are not physical evidence.
