# 02 — Android application components

Runtime responsibility view, not a claim of strict dependency inversion.

```mermaid
flowchart TB
  subgraph UI["Presentation"]
    SC["Compose Today / History / Insights / Devices / Settings"]
    VM["Feature ViewModels / observable StateFlow"]
    SC --> VM
  end
  subgraph RUN["Recording and domain workflow"]
    RS["RecordingService - foreground"]
    CO["SessionCoordinatorImpl"]
    SP["SlidingWindowSegmenter / FeatureExtractor"]
    MS["ModelSelectionEngine"]
    AUTO["BackgroundAutomationCoordinator"]
  end
  subgraph DEV["Transport and inputs"]
    ACC["Android accelerometer"]
    SWITCH["SwitchingDeviceTransport"]
    GATT["AndroidGattDeviceTransport"]
    WS["WebSocketWearableTransport"]
    GATT --> SWITCH
    WS --> SWITCH
  end
  subgraph DATA["Persistence and cloud"]
    ROOM[("Room v8 - sessions, samples, windows, predictions")]
    OUT["Durable outbox / CloudOutboxWorker"]
    CLOUD["Firebase RTDB"]
  end
  VM -->|"commands and state"| CO
  ACC --> RS
  SWITCH --> RS
  RS --> CO
  CO --> SP
  SP --> MS
  CO --> ROOM
  MS --> ROOM
  MS --> AUTO
  AUTO --> ROOM
  AUTO --> ACT['Android / wearable actuators']
  ROOM --> OUT
  OUT --> CLOUD
```

`AppContainer` holds manual dependency wiring. Legacy `ble/MockBleConnector` is a separate diagnostic API and is not shown as production `DeviceTransport`.
