# Data pipeline

Phone `SensorEvent` values are copied in `RecordingService`, mapped through a monotonic clock anchor, validated, and submitted to `SessionCoordinatorImpl`. Raw accelerometer rows and HR rows remain separate in Room. Synthetic WebSocket HR is stored with `SourceKind.WEBSOCKET_SIMULATED`; it is never relabeled as BLE or physical data.

Windows contain 20 three-axis samples. The authoritative deployed contracts are recorded in `ml/artifacts/deployment_manifest.json`: accelerometer `[1,20,3]`; optional multimodal HR `[1,2]` as normalized BPM and availability. Current runtime uses an HR sample only when its BPM is 30–240 and its timestamp age is 0–10 seconds. Future and stale HR are excluded. No HR is fabricated or persisted during absence.

Validation policy:

| Condition | Policy |
|---|---|
| NaN/infinite accelerometer | exclude before inference |
| BPM outside 30–240 | reject message |
| malformed/oversized protocol payload | reject; do not crash service |
| duplicate/out-of-order WebSocket sequence | ignore |
| foreign session | ignore |
| HR older than 10 s | mark unavailable; accel-only inference |
| incomplete accelerometer window | defer inference |
| movement spike | retain unless numerically invalid; quality features handle motion |

Predictions retain window, session, model ID, class scores, duration and timestamp. The field named `confidence` is the maximum model score, not calibrated clinical confidence. Raw retention is governed by `DataRetentionPolicy`; schema migration 6→7 is additive and preserves prior tables.

