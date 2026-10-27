# Architecture evidence

| Component | File | Responsibility | Caller | Data flow | Verification |
|---|---|---|---|---|---|
| MainActivity | `app/.../app/MainActivity.kt` | Compose root, navigation, permission gate | Android | UI → service/navigation | Emulator screenshots |
| SleepTrackerApplication | `app/.../app/SleepTrackerApplication.kt` | Application container initialization | Android | process → DI | Source inspection |
| AppContainer | `app/.../di/AppContainer.kt` | Production dependency wiring | Application/UI | Room, coordinator, BLE, cloud | Source + E2E tests |
| TodayViewModel | `app/.../ui/today/TodayViewModel.kt` | Product recording state | Compose | coordinator state → UI | Runtime screenshots |
| RecordingService | `app/.../service/RecordingService.kt` | Foreground sensor/BLE acquisition | MainActivity | sensor events → coordinator | Runtime recording |
| SessionCoordinatorImpl | `app/.../domain/recording/SessionCoordinatorImpl.kt` | Session state, persistence, summary | Service | samples/windows/predictions → Room | JVM/process recovery/E2E |
| SignalProcessingPipeline | `app/.../domain/recording/SignalProcessingPipeline.kt` | Feature construction | Service/coordinator | samples → windows | DataFlow E2E |
| SensorWindowBuffer | `app/.../ml/SensorWindowBuffer.kt` | 20×3 input window | pipeline | XYZ → tensor | JVM + Android ML tests |
| ActivityClassifier | `app/.../ml/ActivityClassifier.kt` | TFLite inference | pipeline | tensor → 3 probabilities | 12 golden vectors |
| SmartRuleEngine | `app/.../smart/SmartRuleEngine.kt` | deterministic match/rearm/cooldown | Smart scenarios/runtime | input → decision | JVM tests |
| SmartRuleEventCoordinator | `app/.../smart/SmartRuleEventCoordinator.kt` | durable execution/outbox protocol | SmartScenariosViewModel | execution + payload → Room | 3 Android tests |
| RoomSessionRepository | `app/.../data/sessions/RoomSessionRepository.kt` | session aggregate persistence | coordinator | domain objects ↔ Room v7 | Android E2E/migrations |
| Cloud outbox | `app/.../cloud/CloudOutboxPersistence.kt` | FIFO durable operations | coordinator/rules/worker | Room → WorkManager | JVM + Android cloud tests |
| AndroidGattDeviceTransport | `app/.../data/ble/AndroidGattDeviceTransport.kt` | real GATT central | Devices/service | notifications/commands/ACK | source + protocol tests; no physical E2E |
| VirtualGattServer | `virtualsleepband/.../gatt/VirtualGattServer.kt` | companion peripheral | companion UI | advertising/services/notifications | install/launch screenshot |

Production product flow and legacy Lab View are separate UI surfaces. Developer Lab exposes diagnostics; it is not the source of the normal Today recording pipeline. Diagrams are in `architecture/`.
