# 01 — System context and deployment boundaries

Level: system/container view. Solid arrows denote supported interfaces; this is not a Bluetooth-radio test report.

```mermaid
flowchart LR
  subgraph PHONE["Android: SleepSense APK"]
    UI["Compose UI"] --> REC["RecordingService / ML / Room"]
  end
  BAND["VirtualSleepBand APK - synthetic HR server"]
  SENSOR["Physical BLE HR peripheral"]
  FB["Firebase RTDB - opt-in"]
  PY["Python workstation - offline data/ML"]
  BAND -->|"WebSocket TCP 8765 - synthetic HR"| REC
  BAND -.->|"BLE GATT, separate physical device"| REC
  SENSOR -->|"BLE GATT Heart Rate"| REC
  REC -->|"Outbox, WorkManager, auth"| FB
  REC -->|"User export, offline"| PY
  PY -.->|"Promoted TFLite artifacts via build"| REC
```

Grounding: `settings.gradle.kts`, `data/ble/SwitchingDeviceTransport.kt`, `RecordingService.kt`, `virtualsleepband/websocket/VirtualWearableWebSocketServer.kt`, `cloud/CloudRecovery.kt`. WebSocket is not BLE.
