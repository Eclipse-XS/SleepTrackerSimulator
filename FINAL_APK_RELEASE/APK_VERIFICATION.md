# APK Verification Report

**Verification Date**: 2026-11-02
**Branch**: `feature/sleepsense-2.0-overhaul`
**Base Commit**: `00e43108bf00c637ae267cd67a3d1e902ced7341`
**Tooling**: Android SDK Build-Tools 36.0.0 (`apksigner`, `aapt`), JBR 25, Python 3.11

---

## 1. SleepSense-2.0.apk

- **File Path**: `FINAL_APK_RELEASE/SleepSense-2.0.apk`
- **Signing Classification**: **`DEBUG-SIGNED`** (Internal Testing & Development Build)
- **SHA-256 Digest**: `a90a00c77898034ae265f7b78f4ac90c25daec286938b38b8b0402624a6f6558`
- **Package ID**: `com.example.sleeptrackersimulator`
- **Version Code**: `2000001`
- **Version Name**: `2.0-RC1`
- **minSdk**: `31` (Android 12)
- **targetSdk**: `37`
- **Compile SDK**: `37`
- **Launcher Activity**: `com.example.sleeptrackersimulator.app.MainActivity`
- **Signature Scheme**: APK Signature Scheme v2 (RSA 2048, SHA-256)
- **Signer Identity**: `CN=Android Debug, O=Android, C=US`
- **Certificate SHA-256**: `96:74:8e:b9:fc:e9:4c:34:fc:78:db:b1:0a:2f:c5:37:1f:e5:89:b7:ec:cd:87:dc:20:e1:e1:8e:fe:bf:cd:9f`
- **`apksigner verify --verbose`**: `PASS` (Verified using v2 scheme)
- **Declared Permissions**:
  - `android.permission.INTERNET`
  - `android.permission.ACCESS_NETWORK_STATE`
  - `android.permission.VIBRATE`
  - `android.permission.CAMERA`
  - `android.permission.WAKE_LOCK`
  - `android.permission.POST_NOTIFICATIONS`
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_HEALTH`
  - `android.permission.ACTIVITY_RECOGNITION`
  - `android.permission.BLUETOOTH_SCAN`
  - `android.permission.BLUETOOTH_CONNECT`
  - `android.permission.BLUETOOTH_ADVERTISE`
  - `android.permission.RECEIVE_BOOT_COMPLETED`
  - `com.example.sleeptrackersimulator.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- **Embedded ML Assets**:
  - `assets/activity_classifier.tflite` (present and verified)
  - `assets/multimodal_classifier.tflite` (present and verified)

---

## 2. VirtualSleepBand.apk

- **File Path**: `FINAL_APK_RELEASE/VirtualSleepBand.apk`
- **Signing Classification**: **`DEBUG-SIGNED`** (Internal Testing & Development Build)
- **SHA-256 Digest**: `4446a580c2127015e4df7e33fa74f7fb9dedec9d61e70f2cf10169562a0b5070`
- **Package ID**: `com.example.virtualsleepband`
- **Version Code**: `2000001`
- **Version Name**: `2.0-RC1`
- **minSdk**: `26` (Android 8.0)
- **targetSdk**: `37`
- **Compile SDK**: `37`
- **Launcher Activity**: `com.example.virtualsleepband.MainActivity`
- **Signature Scheme**: APK Signature Scheme v2 (RSA 2048, SHA-256)
- **Signer Identity**: `CN=Android Debug, O=Android, C=US`
- **Certificate SHA-256**: `96:74:8e:b9:fc:e9:4c:34:fc:78:db:b1:0a:2f:c5:37:1f:e5:89:b7:ec:cd:87:dc:20:e1:e1:8e:fe:bf:cd:9f`
- **`apksigner verify --verbose`**: `PASS` (Verified using v2 scheme)
- **Declared Permissions**:
  - `android.permission.BLUETOOTH` (maxSdkVersion=30)
  - `android.permission.BLUETOOTH_ADMIN` (maxSdkVersion=30)
  - `android.permission.BLUETOOTH_ADVERTISE`
  - `android.permission.BLUETOOTH_CONNECT`
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE`
  - `android.permission.POST_NOTIFICATIONS`
  - `android.permission.VIBRATE` (**P0 Defect Fixed & Verified in Manifest and Package**)
  - `com.example.virtualsleepband.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

---

## 3. Signing Semantics & Release Security Rules

Both `app/build.gradle.kts` and `virtualsleepband/build.gradle.kts` implement explicit release signing enforcement:
1. **Explicit Credential Configuration**:
   When environment variables or Gradle project properties (`RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) are specified, Gradle validates file existence and credentials, signing the release artifact as **`PRODUCTION-SIGNED`**.
2. **Production Signing Mandate**:
   If `REQUIRE_PRODUCTION_SIGNING=true` is set (e.g. in Play Store publishing CI workflows), Gradle **strictly fails the build** if production credentials are absent. Silent substitution of debug keys is completely prohibited.
3. **Internal Testing Fallback**:
   When `REQUIRE_PRODUCTION_SIGNING` is not enabled and release keys are not provided, Gradle explicitly logs a prominent warning at build time:
   `WARNING: Production signing credentials not provided. Release artifact will be DEBUG-SIGNED for internal testing only.`
   Artifacts generated under this fallback are explicitly classified as **`DEBUG-SIGNED`**.
