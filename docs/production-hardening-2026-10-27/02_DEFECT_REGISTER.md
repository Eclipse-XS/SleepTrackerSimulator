# Defect register

| Priority | Defect and evidence | Root cause | Status / verification |
|---|---|---|---|
| P0 | Sensor callback launched one coroutine per event and read the reusable `SensorEvent.values` later. | Escaping framework-owned mutable buffer; unbounded coroutine production. | IMPLEMENTED_NOT_DEVICE_VERIFIED: synchronous snapshot, bounded queue, explicit drop counter. |
| P0 | Duplicate start could fail coordinator start and stop the already active service. | Commands were processed in independent coroutines without idempotency. | IMPLEMENTED_NOT_DEVICE_VERIFIED: serialized command actor and idempotent transitions. |
| P1 | Samples queued before pause/stop could be attributed to a later session. | No ingestion generation/session token. | IMPLEMENTED_NOT_DEVICE_VERIFIED: epoch tagging rejects stale samples. |
| P1 | Devices UI is wired to `MockBleConnector`; recording transport is separately wired to `MockDeviceTransport`. | Parallel legacy and production BLE abstractions are not integrated. | NOT_FIXED. Real GATT implementation exists but is not the consumer path. |
| P1 | Real GATT marks every standard heart-rate packet `BLE_REAL`, including VirtualSleepBand packets. | Transport authenticity is conflated with measurement provenance. | NOT_FIXED. Protocol needs an authenticated provenance characteristic or explicit user-selected source classification. |
| P1 | VirtualSleepBand is Activity-bound and uses manual BPM/battery controls plus independent jitter. | No long-lived simulator service or correlated scenario generator. | NOT_FIXED. |
| P1 | No in-app 90-night demo generator/import/reset/export integrity workflow was found. | Required demo subsystem absent. | NOT_FIXED. |
| P1 | Release is not signed/configured as a Play artifact; hardware acceptance evidence is unavailable. | Environment and release configuration gaps. | BLOCKED / NOT_FIXED. |
| P2 | Insights used a 7/30/90 selector despite explicit product requirement. | Date-window API drove UI. | FIXED_AND_VERIFIED by compilation/tests after full-history API change. |
| P2 | Companion module has no unit tests. | Test source set absent. | NOT_FIXED. |
