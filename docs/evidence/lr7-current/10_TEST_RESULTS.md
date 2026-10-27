# Test results

| Suite | Environment | Count | Passed | Failed | Evidence | Status |
|---|---|---:|---:|---:|---|---|
| JVM unit | Windows/Gradle/JDK25 daemon | 197 | 197 | 0 | `test-results/junit/*.xml` | PASS |
| Android instrumentation | Pixel_10_Pro AVD API 36; direct AndroidJUnitRunner | 22 | 22 | 0 | `test-results/androidTest/manual_instrumentation_summary.txt` | PASS |
| Firebase Rules | RTDB emulator, demo project | 11 | 11 | 0 | `test-results/firebase/firebase_rules_summary.txt` | PASS |
| ML evaluation | Python venv / synthetic holdout 750 | 4 models | 4 evaluated | 0 | `test-results/python/model_comparison.json` | PASS |
| CSV validation | Python/pandas synthetic benchmark | 1 | 1 | 0 | `test-results/python/dataset_validation_summary.txt` | PASS |
| Build/lint | Gradle | 6 tasks/groups | 6 | 0 | command below | PASS |

Gradle command: `.\gradlew :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:lintVitalRelease :app:assembleDebug :app:assembleRelease :virtualsleepband:assembleDebug`; BUILD SUCCESSFUL in 5m23s.

Android run included CloudOfflineE2E, CorrelatedPipelineE2E, DataFlowE2E, migrations, 12 golden vectors, SmartRule persistence and atomic coordinator recovery. `connectedDebugAndroidTest` was not used as proof in this run; direct `am instrument -w -r` produced `OK (22 tests)` in 2.915 s.
