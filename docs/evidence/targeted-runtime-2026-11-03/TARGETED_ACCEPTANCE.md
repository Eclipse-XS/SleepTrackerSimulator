# Targeted UI and WebSocket acceptance — 2026-11-03

Environment: Pixel 10 Pro AVD, Android API 36.1, 16 KB system image, emulator-5554. Both APKs were installed as independent packages in the same AVD. VirtualSleepBand listened on `0.0.0.0:8765`; SleepSense connected to `ws://127.0.0.1:8765`.

| Scenario | Result | Runtime evidence |
|---|---|---|
| WebSocket endpoint editor and persistence | PASS | Endpoint remained `ws://127.0.0.1:8765` after process restart and APK replacement install. Invalid schemes/ports are covered by unit tests. |
| Availability verification | PASS | Scan exposed a candidate only after `HELLO` / `DEVICE_INFO`; Logcat: `DEVICE_INFO accepted ... device=virtual-sleep-band-ws-01`. |
| Independent-process HR stream | PASS | Repeated Logcat samples: sequences 2, 4, 6, 8, 10 with `provenance=WEBSOCKET_SIMULATED`; Devices showed `HR STREAM ACTIVE`. |
| Phone + wearable recording | PASS | Today showed `Phone + Wearable · Accel + HR · Multimodal TFLite`; RecordingService remained foreground. |
| Force-stop and stale fallback | PASS | After force-stopping VirtualSleepBand, recording continued and showed `Accelerometer LiteRT` plus `Fallback context: stale_heart_rate_13612ms`. |
| Restart and reconnect | PASS | New server process logged `Listening on 0.0.0.0:8765`; reconnect produced a new session and repeated HR samples. `SO_REUSEADDR` allowed immediate bind while old accepted sockets remained in TIME_WAIT. |
| Demo import | PASS | UI reported `Loaded 90 demo nights and 4320 predictions`; Room counter reported 90/4320. |
| Demo persistence | PASS | Generated session details and Demo Insights remained visible after force-stop/restart and replacement install. |
| Demo reset isolation | PASS | Room counter changed to 0/0; History Real Only still contained four non-demo sessions. |
| Academic Lab View | PASS | Header started below the 156 px status/cutout inset; legacy simulation and heuristic labels were explicit; X/Y/Z were separate equal-width cells. |
| Android unit tests and APK builds | PASS | `testDebugUnitTest`, `:app:assembleDebug`, and `:virtualsleepband:assembleDebug`: BUILD SUCCESSFUL, 92 Gradle tasks. |
| Data/ML/Firebase regressions | PASS | `uv run --frozen pytest tests/data tests/ml tools/tests/test_firebase_control.py -q`: 80 passed, 6 subtests passed. |
| APK 16 KB zip alignment | PASS | `zipalign -c -P 16 -v 4` succeeded for both APKs. |
| Physical BLE and physical actuators | NOT_VERIFIED | No secondary BLE hardware or actuator hardware was used. WebSocket evidence is not BLE evidence. |

Screenshots:

- `academic-lab-connected.png`
- `academic-lab-axes.png`
- `devices-websocket-final.png`
- `devices-websocket-connected-final.png`
- `demo-session-detail.png`
- `demo-insights.png`

The Demo Insights screenshot contains 91 simulated sessions because it was captured after the 90-night import and one independent WebSocket recording. The generator/import count itself was separately verified as exactly 90 sessions and 4,320 predictions.
