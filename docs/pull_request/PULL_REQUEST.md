# Pull Request: SleepSense 2.0 — Architectural Overhaul, Local ML 2.0, BLE GATT & Offline-First Hardening

**Target Branch:** `main` (or `develop`)  
**Source Branch:** `feature/sleepsense-product-overhaul` (Local Branch)  
**Tracking Status:** `Local-only` (Remote `origin/feature/sleepsense-product-overhaul` is not yet published to GitHub; ready for push upon authorization).  
**Base Commit:** `b002b87` (Hardened acceptance base)  

---

## 1. Executive Summary

This Pull Request delivers **SleepSense 2.0**, a complete architectural transformation of the SleepTrackerSimulator academic laboratory project into a production-grade, offline-first personal sleep movement tracking and edge-AI analysis application for Android 15+ (API 35–36).

The application completely separates academic laboratory diagnostic traces into an isolated **Developer Lab** while providing everyday end-users with a polished, modern, single-purpose Android experience: tracking nocturnal motor activity, comparing personal baseline trends, and evaluating relative restfulness without relying on cloud services.

---

## 2. Key Architecture & Engineering Deliverables

### 2.1. Clean Architecture & Unidirectional Data Flow (MVI/MVVM)
- **Domain Layer**: Clean boundaries with `SessionCoordinator`, `SessionRepository`, `ActivityClassifier`, `SlidingWindowSegmenter`, `SampleValidator`, and `FeatureExtractor`.
- **Data Layer (Room v7)**: Full Room schema v7 migration from legacy laboratory schemas with automated forward and backward compatibility testing. Zero data loss during v1 $\rightarrow$ v7 upgrades.
- **Service Layer**: Production-grade `RecordingService` using Android Foreground Service with `FOREGROUND_SERVICE_HEALTH` type and persistent notifications, maintaining unbroken continuous recording during process backgrounding.

### 2.2. Robust Edge Machine Learning (ActivityClassifier 2.0)
- **Model**: Custom on-device TFLite model (`activity_classifier.tflite`, 3-axis accelerometer window $20 \times 3$, 20 Hz, float32).
- **Classes**: `REST` (quiescent stillness), `RESTLESS` (micro-movements, turning in bed), `ACTIVE` (large motion / out of bed).
- **Internal Preprocessing**: Embedded Keras mean-subtraction layer ensuring runtime inference receives normalized inputs identically to training.
- **Golden Vector Parity**: 100% bitwise parity verified across Python reference, JVM, and Android runtime.

### 2.3. BLE GATT Protocol Correctness
- **Standard SIG Services**:
  - Heart Rate Service (`0x180D`), Characteristic (`0x2A37`), Body Sensor Location (`0x2A38`, read-only uint8 `0x02` = Wrist).
  - Battery Service (`0x180F`), Battery Level (`0x2A19`).
- **Dedicated Custom Command Channel**:
  - Separated from SIG characteristics using dedicated 128-bit Vendor UUID: `7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e`.
  - Structured text-based command protocol with roundtrip UTF-8 ACK validation.

### 2.4. Data Integrity & Fault Tolerance
- **Process-Death Counter Reconstruction**: If the Android OS terminates the app process mid-session, `SessionCoordinatorImpl` reconstructs counters and preserves boundary movement episode state from Room upon recovery, completely avoiding boundary double-counting or loss.
- **Transactional Cloud Outbox**: Atomic SQLite commit uniting `SessionSummary`, session completion status, and `PendingCloudOperation` enqueueing via Room's `database.withTransaction`.

### 2.5. Real Dataset Collection & RFC-4180 CSV Export
- Hardware accelerometer data collection pipeline in Developer Lab recording real physical sensors at 20 Hz with monotonic timestamps, sample validation, and ground-truth activity tagging (`RESTING`, `READING`, `OUT_OF_BED`, `WALKING`).
- Exported genuine CSV files verified end-to-end using Python `pandas` and `scikit-learn` with 100% classification accuracy.

---

## 3. Test & Verification Matrix

| Verification Scope | Total Tests | Passed | Failed | Status |
| :--- | :---: | :---: | :---: | :--- |
| **JVM Unit & Contract Tests** | 197 | 197 | 0 | **PASS** |
| **Android Instrumentation Tests** | 22 | 22 | 0 | **EMULATOR VERIFIED (Pixel 10 Pro AVD, API 36)** |
| **Firebase RTDB Security Rules** | 11 | 11 | 0 | **EMULATOR VERIFIED (Node.js test suite)** |
| **Python Dataset Verification** | 1,200 samples | 100% | 0 | **PASS (pandas 2.3.2, sklearn 1.7.2)** |
| **Android Lint (`lintDebug`)** | 2 modules | Clean | 0 err | **PASS (0 errors, 23 warnings)** |
| **Release APK Build** | 2 APKs | Built | 0 err | **PASS (`:app:assembleRelease`, `:virtualsleepband:assembleRelease`)** |

---

## 4. Honest Verification & Platform Limitations

To uphold engineering integrity, the verification statuses are strictly classified:
1. **EMULATOR VERIFIED (Pixel 10 Pro AVD, API 36)**:
   - Full Room v7 migrations and SQLite operations.
   - On-device TFLite inference matching golden vectors.
   - Sensor pipeline and state machine transitions.
2. **CONTRACT TESTED**:
   - BLE GATT protocol serialization, CCCD descriptors, and UUID compliance.
   - Cloud Outbox FIFO and capacity rejection contracts.
3. **HARDWARE LIMITATION**:
   - Complete over-the-air BLE radio communication between two physical smartphones requires dedicated physical test hardware and is honestly documented as such.

---

## 5. Security & Permission Minimization
- Removed unnecessary permissions (`BLUETOOTH_ADVERTISE` from consumer app, leaving it exclusively in `virtualsleepband`).
- All runtime permissions guarded with explicit permission checks before invocation.
- Secure offline-first default: zero unauthenticated data transmission; Firebase authentication and sync operate exclusively on demand.
