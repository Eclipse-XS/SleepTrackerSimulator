# Visible control mapping

| Screen/control | Callback | Execution path | Observable result |
|---|---|---|---|
| Today / Start, Pause, Resume, Finish | `RecordingService` commands | Foreground service → `SessionCoordinator` → Room | Recording state, notification, persisted summary |
| History filters/details/delete | `HistoryViewModel` | `RoomSessionRepository` | Filtered persisted sessions/details |
| Insights Real/Demo | `InsightsViewModel` | persisted summaries Flow | isolated metrics and trend |
| Devices mode/scan/connect/disconnect | `DevicesViewModel` | `SwitchingDeviceTransport` → mock or Android GATT | explicit mode/state/device/provenance |
| Settings / Open Data Export Tools | overlay navigation | Developer Lab → Room | working CSV/demo data tools; no dead export callback |
| Settings / Delete All Local Data | confirmation callback | `RoomSessionRepository.clearAllLocalData()` | all Room tables cleared |
| Settings / Firebase information | read-only build status | build-specific Firebase config | no fake local toggle |
| Developer Lab / Flashlight ON/OFF | `requestFlashlightChange(Boolean)` | permission → `ActuatorViewModel` → `AndroidSmartActuator` → `CameraManager` | requested/API/TorchCallback state |
| Developer Lab / Emergency OFF | `ActuatorViewModel.emergencyOff()` | disables automatic, cancels vibration, forces torch OFF, safety latch | all requested states OFF and API diagnostic |
| Developer Lab / Vibration pulse/persistent | `ActuatorViewModel` | `VibratorManager`/`VibrationEffect` | availability/API/error; physical confirmation remains separate |
| Developer Lab / Automatic Control | `setAutomaticControlEnabled` | decision engine | visible control mode |
| Developer Lab / Room buttons | `DemoDatasetViewModel` | generator/repository/Room verifier/export | counts and integrity message |
| Legacy Lab / BLE controls | `BleViewModel` | educational mock connector | mock device/log/command state |
| VirtualSleepBand controls | service callbacks | foreground service → GATT server | advertising/scenario/BPM/battery/command log |

The prior constant `requestFlashlightChange(true)` callback and the Settings no-op export/fake cloud toggle were removed.
