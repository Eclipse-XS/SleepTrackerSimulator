# Known limitations and technical debt

## Scientific validity

- The TFLite models were trained and evaluated on synthetic signals. Classification is movement intensity, not clinical sleep staging.
- Nightstand placement can mechanically decouple the phone sensor from body movement; shifted synthetic evaluation reports a material macro-F1 decrease.
- Model scores are not clinically calibrated probabilities. Real longitudinal multi-subject validation is not included.

## Device and infrastructure

- WebSocket testing confirms inter-process exchange; it does **not** validate Bluetooth RF/GATT interoperability.
- The virtual WebSocket endpoint accepts only `ws://` and has no TLS or cryptographic pairing. Use on an isolated development network.
- Physical BLE alerts, actuator hardware execution and real Firebase E2E have limited/no independently verified device evidence.
- Android background behavior depends on OS power management, permissions and hardware characteristics.

## Code architecture

- `SessionCoordinatorImpl` coordinates lifecycle, ingestion, prediction, persistence and summary logic. It is large and directly depends on `cloud/`-named contracts despite living under `domain/`.
- The legacy `ble/MockBleConnector` API coexists with `data/ble/DeviceTransport`. It remains intentionally wired for legacy/diagnostic use; it should not be mistaken for production external-HR transport.
- `wearable-protocol/` is shared source compiled twice using Gradle source sets, not a separately versioned Gradle module.
- Some Compose UI screens and ViewModels are large; breaking them into components should be a separate tested refactor rather than a documentation-only move.

These are known tradeoffs, not proof of runtime defects. Functional refactoring should follow a separate PR with full Android regression coverage.
