# Release Readiness & Google Play Compliance

## 1. Release Artifacts & Checksums

Fresh debug APKs have been compiled and verified in `FINAL_APK_RELEASE/`:

| Package | Application Name | Version | Min SDK | Target SDK | Size | SHA-256 Checksum |
|:---|:---|:---|:---|:---|:---|:---|
| `com.example.sleeptrackersimulator` | `SleepSense 2.0` | 2.0.0 (Code 2) | 26 | 35 | ~85.9 MB | `6f5ec2e53fb9213ffd7c86d0e1d9b158c63b86336b9ff298e2926c411475e8fb` |
| `com.example.virtualsleepband` | `VirtualSleepBand` | 1.0.0 (Code 1) | 26 | 35 | ~17.7 MB | `763077e590d276e9a62b32141547f438dcdefe26522ad3ab0de9d154e3457d37` |

Both artifacts were signed with the standard Android debug keystore and verified with `apksigner`.

---

## 2. Google Play Policy Compliance Assessment

### 1. Health App Policy & Medical Disclaimers
- **Compliance Status**: `COMPLIANT`.
- **Policy Contract**: Applications that track sleep, heart rate, or physiological metrics must not claim clinical diagnostic accuracy unless clinically validated and certified.
- **Implementation**:
  - `TodayScreen` and `PlacementGuideScreen` prominently display:
    > *"SleepSense provides general wellness and rest quality estimation based on movement and heart rate telemetry. It is not a medical device and is not intended to diagnose, treat, or monitor any medical condition or sleep disorder."*

### 2. Android 14+ Foreground Service Types
- **Compliance Status**: `COMPLIANT`.
- **Declaration**: `RecordingService` declares `android:foregroundServiceType="health"` in `AndroidManifest.xml`.
- **Justification**: Continuous sleep movement recording and BLE heart rate ingestion require active foreground service execution while the smartphone screen is turned off overnight.

### 3. Bluetooth Runtime Permissions (API 31+)
- **Compliance Status**: `COMPLIANT`.
- **Permissions Declared**:
  - `android.permission.BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"`.
  - `android.permission.BLUETOOTH_CONNECT`.
  - `android.permission.BLUETOOTH_ADVERTISE` (in VirtualSleepBand).
  - Legacy `android.permission.ACCESS_FINE_LOCATION` with `android:maxSdkVersion="30"`.

---

## 3. Production Signing Configuration (Issue 25)

For Google Play production deployment, release signing is configured in `app/build.gradle.kts`:
- Protects production keystores from accidental repository commits.
- Uses environment variables:
  - `RELEASE_KEYSTORE_PATH`
  - `RELEASE_KEYSTORE_PASSWORD`
  - `RELEASE_KEY_ALIAS`
  - `RELEASE_KEY_PASSWORD`
- Falls back to debug signing when environment variables are omitted, ensuring clean local developer builds.
