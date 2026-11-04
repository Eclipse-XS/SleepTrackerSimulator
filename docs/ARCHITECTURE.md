# SleepSense 2.0 — implemented architecture

**Scope:** repository `main` at `f03008bb269910d31ec17df99f902d66695cbf05`, documented 2026-11-04. This describes implemented components, not a proposed target architecture.

## Runtime boundaries

| Boundary | Implementation | Responsibility |
| --- | --- | --- |
| Android application | `app/` | UI, recording, inference, automation, local persistence, opt-in cloud |
| Independent companion APK | `virtualsleepband/` | Synthetic HR generation, WebSocket server and a BLE GATT peripheral implementation |
| Shared source (not a Gradle module) | `wearable-protocol/src/main/kotlin` | Wire messages; added to both apps' Kotlin `sourceSets` |
| Android wearable adapter | `data/ble/SwitchingDeviceTransport.kt` | Select `REAL` (Android GATT) or `VIRTUAL_WEBSOCKET` (inter-process TCP/WebSocket) |
| Python analysis | `pipelines/`, `ml/` | Export ingestion, synthetic experiments, data quality, training and model promotion |

Two Gradle Android modules are declared in `settings.gradle.kts`: `:app` and `:virtualsleepband`. Do not present `wearable-protocol/` as a separate Gradle module unless the build is actually refactored.

## Android execution

Jetpack Compose screens and ViewModels observe application state. `RecordingService` registers the Android accelerometer and collects wearable heart-rate data while recording; it submits data to `SessionCoordinatorImpl`. The coordinator validates samples, builds windows, persists sensor and ML records in Room and delegates rules to `BackgroundAutomationCoordinator`.

The default requested phone rate is 20 Hz; `RecordingService` accepts a configurable rate. `SlidingWindowSegmenter(windowSize=20, stride=20)` creates non-overlapping 20-sample windows. These represent nominal one-second analysis windows at 20 Hz, not a guaranteed exact duration or fixed sensor delivery rate.

`ModelSelectionEngine` prefers the multimodal TFLite model for fresh valid heart rate, otherwise tries accelerometer-only TFLite; low-confidence or unavailable inference can trigger a documented rule-based fallback. Incomplete/non-finite input is not silently labeled `REST`.

## Persistence, cloud and protocols

Room schema **v8** is the current local relational contract: `app/schemas/com.example.sleeptrackersimulator.history.SleepTrackerDatabase/8.json`. Raw accelerometer and HR samples retain separate provenance. Cloud delivery uses durable Room outbox state with `WorkManager` recovery, subject to user consent and authentication. A committed local record does not imply successful remote delivery.

`SwitchingDeviceTransport` chooses between GATT (real Bluetooth radio required) and WebSocket (a separate VirtualSleepBand process on TCP 8765). The legacy `MockBleConnector` is instantiated for legacy/diagnostic surfaces in `AppContainer`; it is distinct from the production `DeviceTransport` implementation.

## Architecture quality and known debt

The present structure uses recognizable UI, domain, data, background service and storage boundaries, but it is **not strict Clean Architecture**: `domain/recording/SessionCoordinatorImpl.kt` directly imports `cloud/*` contracts, and legacy `ble/*` coexists with `data/ble/*`. Some Compose screens and the coordinator are large. Refactoring these is a separate code change with regression risk; folder renames alone would not fix dependency direction.

## Diagrams

- [System context](diagrams/01-system-context.md)
- [Android components](diagrams/02-android-components.md)
- [Data and ML flow](diagrams/03-data-flow.md)
- [Room v8 schema](diagrams/04-room-er.md)
- [Session lifecycle](diagrams/05-session-lifecycle.md)
- [Automation and cloud](diagrams/06-rules-cloud-sequence.md)
