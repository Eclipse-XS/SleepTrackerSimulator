# SLEEPSENSE 2.0 — INDEPENDENT ACCEPTANCE AUDIT
## 05. BLE GATT Architecture & Peripheral Verification

**Audit Date:** 2026-10-27  
**Subsystems Audited:**
1. `VirtualSleepBand` (In-process synthetic simulator)
2. `VirtualSleepBandAdvertiser` (Android BLE GATT Server & Advertiser)
3. `MockDeviceTransport` & `AndroidGattDeviceTransport`
4. Central Scanner & Reconnection Logic

---

### 1. BLE Protocol Contract

| Service / Characteristic | UUID | Type | Properties | Purpose |
|---|---|---|---|---|
| **Heart Rate Service** | `0x180D` | Primary Service | - | Standard SIG Heart Rate |
| **Heart Rate Measurement** | `0x2A37` | Characteristic | Notify | Emits heart rate flags + BPM (uint8) + optional RR intervals |
| **Body Sensor Location** | `0x2A38` | Characteristic | Read | Returns `0x02` (Wrist) |
| **Battery Service** | `0x180F` | Primary Service | - | Standard Battery Monitoring |
| **Battery Level** | `0x2A19` | Characteristic | Read / Notify | Battery percentage (0–100%) |
| **Command Characteristic** | `7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e` | Characteristic | Write | `START_STREAM`, `STOP_STREAM`, `REQUEST_STATUS` |

---

### 2. Dual-Mode Implementation Strategy

To satisfy both automated headless testing and real Android hardware verification, SleepSense provides two complementary implementations:

1. **`MockDeviceTransport` (Universal In-Process Transport):**
   - Implements `DeviceTransport`.
   - Simulates complete peripheral state machine (`Disconnected` -> `Scanning` -> `Connecting` -> `Connected` -> `Streaming`).
   - Generates photoplethysmogram-correlated heart rate samples with physiological baseline wander (55–85 BPM).
   - Zero physical Bluetooth dependencies: enables deterministic CI/CD and instrumentation testing without flake.

2. **`VirtualSleepBandAdvertiser` (On-Device GATT Server & Advertiser):**
   - Uses Android `BluetoothManager.openGattServer()` and `BluetoothLeAdvertiser.startAdvertising()`.
   - Turns an Android device or second phone into an actual physical BLE peripheral.
   - Handled directly in `DeveloperLabScreen` (BLE Tab) with one-click start/stop.
   - When running on an emulator without Bluetooth hardware, safely informs the user (`Bluetooth adapter not available or disabled`) without crashes.

---

### 3. Data Integrity & Clock Correlation Policy

- **Isolation Principle:** Wearable heart rate signals (even from physical peripherals) are flagged with `SourceKind.BLE_REAL` or `SourceKind.SIMULATED`.
- **Zero Pollution:** Simulated wearable data is strictly isolated in `heart_rate_samples` and **never** mixed into the phone accelerometer movement baseline calculations.
- **Clock Correlation:**
  - Phone accelerometer uses `SystemClock.elapsedRealtimeNanos()`.
  - Wearable samples carry UTC timestamps synchronized via the calibrated `ClockAnchor`.

**Verdict:** `IMPLEMENTED` & `COMPILED` & `EXECUTED` (Full simulated pipeline active; GATT Server/Advertiser integrated).
