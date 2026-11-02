# SleepSense 2.0 — Final Repository Baseline & Pre-Audit State

**Date & Time**: 2026-11-02T20:57:30+02:00
**Repository Root**: `C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator`
**Remote Origin**: `https://github.com/Eclipse-XS/SleepTrackerSimulator.git`
**Starting Active Branch**: `feature/sleepsense-2.0-overhaul`
**Starting HEAD SHA**: `00e43108bf00c637ae267cd67a3d1e902ced7341`

---

## 1. Remote & Branch Status

```
$ git remote -v
origin  https://github.com/Eclipse-XS/SleepTrackerSimulator.git (fetch)
origin  https://github.com/Eclipse-XS/SleepTrackerSimulator.git (push)

$ git branch -a
* feature/sleepsense-2.0-overhaul
  main
  feature/sleepsense-product-overhaul
  feature/lab6-on-device-ai
  feature/lab5-room-analytics
  remotes/origin/main
  remotes/origin/feature/sleepsense-product-overhaul
  remotes/origin/feature/lab6-on-device-ai
  remotes/origin/feature/lab5-room-analytics
```
The remote branch `origin/feature/sleepsense-2.0-overhaul` does not yet exist. A clean, non-force push will publish this branch directly to origin without modifying `main` or any legacy lab branches.

---

## 2. Working Tree Baseline (`git status -s`)

```
 M app/build.gradle.kts
 M app/src/main/java/com/example/sleeptrackersimulator/actuator/AndroidSmartActuator.kt
 M app/src/main/java/com/example/sleeptrackersimulator/app/MainActivity.kt
 M app/src/main/java/com/example/sleeptrackersimulator/cloud/CloudModels.kt
 M app/src/main/java/com/example/sleeptrackersimulator/cloud/CloudOutboxPersistence.kt
 M app/src/main/java/com/example/sleeptrackersimulator/cloud/CloudRecovery.kt
 M app/src/main/java/com/example/sleeptrackersimulator/cloud/FirebaseCloudRepository.kt
 M app/src/main/java/com/example/sleeptrackersimulator/cloud/PersistentCloudStateStore.kt
 M app/src/main/java/com/example/sleeptrackersimulator/data/ble/AndroidGattDeviceTransport.kt
 M app/src/main/java/com/example/sleeptrackersimulator/di/AppContainer.kt
 M app/src/main/java/com/example/sleeptrackersimulator/domain/recording/SessionCoordinatorImpl.kt
 M app/src/main/java/com/example/sleeptrackersimulator/service/RecordingService.kt
 M app/src/main/java/com/example/sleeptrackersimulator/smart/SmartRuleEventCoordinator.kt
 M app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/CloudSyncViewModel.kt
 M app/src/main/java/com/example/sleeptrackersimulator/ui/history/HistoryViewModel.kt
 M app/src/main/java/com/example/sleeptrackersimulator/ui/settings/SettingsScreen.kt
 M app/src/main/java/com/example/sleeptrackersimulator/ui/today/TodayScreen.kt
 M app/src/main/java/com/example/sleeptrackersimulator/ui/today/TodayViewModel.kt
 M app/src/test/java/com/example/sleeptrackersimulator/data/sessions/SessionRepositoryTest.kt
 M app/src/test/java/com/example/sleeptrackersimulator/domain/recording/SessionCoordinatorTest.kt
 M app/src/test/java/com/example/sleeptrackersimulator/ui/dashboard/CloudSyncViewModelTest.kt
 M app/src/test/java/com/example/sleeptrackersimulator/ui/today/TodayViewModelTest.kt
 M docs/ml/MODEL_CARD.md
 M pipelines/cli/main.py
 M pipelines/features/feature_extractor.py
 M virtualsleepband/build.gradle.kts
 M virtualsleepband/src/main/AndroidManifest.xml
 M virtualsleepband/src/main/java/com/example/virtualsleepband/gatt/VirtualGattServer.kt
?? FINAL_APK_RELEASE/
?? app/src/main/java/com/example/sleeptrackersimulator/cloud/CloudSyncConsentRepository.kt
?? app/src/test/java/android/os/TestVibrator.java
?? app/src/test/java/com/example/sleeptrackersimulator/actuator/SmartActuatorSafetyTest.kt
?? app/src/test/java/com/example/sleeptrackersimulator/cloud/CloudErasureConsentPolicyTest.kt
?? app/src/test/java/com/example/sleeptrackersimulator/cloud/FirebaseCloudRepositoryConsentTest.kt
?? app/src/test/java/com/example/sleeptrackersimulator/data/ble/AndroidGattDeviceTransportTest.kt
?? app/src/test/java/com/example/sleeptrackersimulator/ui/history/HistoryViewModelTest.kt
?? data/
?? docs/audit/
?? docs/datascience/
?? ml/data/
?? ml/eda/
?? ml/experiments/
?? ml/monitoring/
?? ml/simulation/
?? scripts/
?? sql/
?? tests/datascience/
?? virtualsleepband/src/test/java/com/example/virtualsleepband/gatt/VirtualGattServerPacingTest.kt
```

---

## 3. Large File & Hygiene Risk Assessment

1. **Large SQLite Databases**:
   - `data/synthetic/sleepsense_synthetic.sqlite`: **181.2 MB**
   - `data/synthetic/sleepsense_shifted.sqlite`: **134.8 MB**
   - *Risk*: Exceeds GitHub's 100 MB hard file limit.
   - *Mitigation*: Ensure `*.sqlite`, `*.sqlite3` and `data/synthetic/*.sqlite` are added to `.gitignore`. Dataset reproducibility is guaranteed through deterministic CLI generators (`python -m pipelines.cli.main generate`).
2. **Release APK Binaries**:
   - `FINAL_APK_RELEASE/SleepSense-2.0.apk`: 70.2 MB
   - `FINAL_APK_RELEASE/VirtualSleepBand.apk`: 12.5 MB
   - *Risk*: Binary blobs should not be committed into Git history.
   - *Mitigation*: Exclude `*.apk` from Git staging via `.gitignore`.
3. **Sensitive Data / Credentials**:
   - No `.env`, `google-services.json`, or keystore files are staged.
