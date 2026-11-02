# Bluetooth Low Energy Physical Acceptance Plan

> [!IMPORTANT]
> In accordance with non-negotiable auditing rules, because automated tests were executed in virtual and headless host environments without two physical RF-coupled Android devices, all physical over-the-air claims are classified as **`UNVERIFIED_BY_HARDWARE`**. This document provides the exact physical acceptance procedure required for human verification.

---

## 1. Hardware & Environment Setup

### Required Physical Hardware
1. **Device A (Central)**: Physical Android smartphone running Android 12+ (API 31+) with Bluetooth Low Energy 5.0+ hardware support.
   - Install: `FINAL_APK_RELEASE/SleepSense-2.0.apk`.
2. **Device B (Peripheral Simulator)**: Physical Android smartphone running Android 8.0+ (API 26+) with BLE Peripheral mode support (`BluetoothAdapter.isMultipleAdvertisementSupported() == true`).
   - Install: `FINAL_APK_RELEASE/VirtualSleepBand.apk`.
3. **Environment**: RF-clean room or typical residential bedroom; line-of-sight distance of 1 to 3 meters.

---

## 2. GATT Protocol Contract Verification

### Advertised Services & Characteristics
| Entity | UUID | Purpose | Access |
|:---|:---|:---|:---|
| **Heart Rate Service** | `0000180d-0000-1000-8000-00805f9b34fb` (0x180D) | Standard Bluetooth SIG HR Service | Primary Service |
| **Heart Rate Measurement** | `00002a37-0000-1000-8000-00805f9b34fb` (0x2A37) | Periodic HR telemetry packets | NOTIFY |
| **Client Characteristic Configuration Descriptor (CCCD)** | `00002902-0000-1000-8000-00805f9b34fb` (0x2902) | Enable/disable notifications | READ / WRITE |
| **Command Characteristic** | `7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e` | Remote peripheral actuator actuation | WRITE (Acknowledge) |
| **Manufacturer Data** | ID `0xFFFF`, Payload: `[0x53, 0x53, 0x56, 0x31]` (`SSV1`) | Simulator identification marker | Advertisement Record |

---

## 3. Physical Verification Procedures

### Step 1: Simulator Advertisement & Discovery
1. Launch `VirtualSleepBand` on Device B.
2. Select Heart Rate Profile: **"Resting Baseline (62 BPM)"**. Set Packet Interval: **1000 ms**.
3. Tap **"Start Advertising"**.
4. Verify notification shows: *"VirtualSleepBand advertising as 'Virtual SleepBand'..."*.
5. Launch `SleepSense 2.0` on Device A. Navigate to **"Devices"** tab.
6. Tap **"Scan for Devices"**.
7. **Verification**:
   - Device B appears in the discovered list as *"Virtual SleepBand"*.
   - Provenance indicator displays a simulated badge (due to manufacturer marker `SSV1`).
   - Signal RSSI updates dynamically.

### Step 2: Connection, Service Discovery, and CCCD Subscription
1. On Device A, tap **"Connect"** next to Virtual SleepBand.
2. Observe state progression in UI:
   - `CONNECTING` -> `DISCOVERING_SERVICES` -> `SUBSCRIBING` -> `CONNECTED`.
3. In `VirtualSleepBand` on Device B, observe connected central counter increments to 1.
4. **Verification**:
   - Live Heart Rate tile in SleepSense displays 62 BPM ($\pm 2$ BPM).
   - Packet reception timestamp updates continuously every 1000 ms.

### Step 3: Simulation Pacing Validation (250 ms / 500 ms)
1. On Device B, change Packet Interval to **250 ms**.
2. Run stopwatch on Device A for exactly 60 wall-clock seconds.
3. Observe the internal simulation timestamp reported by VirtualSleepBand.
4. **Verification**:
   - After 60 wall-clock seconds, simulation clock advances by exactly 60 seconds (not 240 seconds).
   - Confirms monotonic clock fix `(nowNanos - simulationStartNanos) / 1_000_000_000L` is active.

### Step 4: Asynchronous Command Execution & Actuator Response
1. On Device A, navigate to Device Diagnostics and trigger a remote alert command:
   - Action: `ALERT_PULSE`.
2. Inspect logcat on Device A (`AndroidGattDeviceTransport`):
   - Command sent via `writeCharacteristic`.
   - `onCharacteristicWrite` callback received with `GATT_SUCCESS`.
   - Result reports: *"Sent command to peripheral"* within $<100$ ms.
3. Observe Device B:
   - Device B vibrates physically for 300 ms.
   - Screen displays: *"Received command: ALERT_PULSE — vibration pulse triggered"*.
4. **Negative Test**: Send invalid command string `INVALID_ACTION`.
   - Device B rejects characteristic write with `BluetoothGatt.GATT_FAILURE`.
   - Device A logs failure and displays appropriate error notification.

### Step 5: Unexpected Disconnect & Auto-Reconnect State Machine
1. While connected and streaming 62 BPM, disable Bluetooth on Device B (or place Device B into RF shielding bag).
2. On Device A, observe:
   - Connection state transitions immediately to `RECONNECTING`.
   - Device A does NOT crash or transition immediately to permanent `ERROR`.
3. Re-enable Bluetooth on Device B within 5 seconds.
4. Device A attempts exponential backoff reconnection:
   - Attempt 1 (1s delay) -> Attempt 2 (2s delay).
5. **Verification**:
   - Device A reconnects successfully, rediscovers services, rewrites CCCD descriptor, and resumes live BPM stream.
6. If Device B remains off for $>15$ seconds (exceeding 3 backoff attempts), Device A cleanly transitions to `DISCONNECTED` / `ERROR`.

### Step 6: End-to-End Recording Session Integration
1. On Device A, navigate to **"Today"** tab.
2. Select **"Combined (Phone + Wearable)"** Source Mode chip.
3. Tap **"Start Observation Session"**.
4. Allow session to record for 5 minutes.
5. Tap **"Stop Session"**.
6. Inspect generated session details in History / Today:
   - Data Coverage Fraction $\ge 0.90$.
   - Average Heart Rate displays ~62 BPM.
   - Data Provenance reflects `SIMULATED` (correctly identifying companion simulator).
   - Room database `session_summaries.averageHeartRateBpm` contains integer 62.
