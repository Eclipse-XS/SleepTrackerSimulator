# Final physical acceptance matrix

| Test | Device | Result | Evidence | Remaining issue |
|---|---|---|---|---|
| Recording without screen recorder | Pixel_10_Pro AVD | PASS_EMULATOR | Completed summaries with 100% coverage | Physical phone not tested |
| Recording with screen recorder | Pixel_10_Pro AVD | PASS_EMULATOR_AFTER_FIX | Native crash reproduced, fixed, and absent on retest | Vendor recorder/affected phone unavailable |
| 30-minute recording | Pixel_10_Pro AVD | PASS_EMULATOR | 30 min 10 sec, stable PID, screen recording, recreation, background/foreground, two screen-off phases, empty crash buffer | Physical-phone endurance remains unverified |
| Flashlight ON/OFF | Pixel_10_Pro AVD | PASS_SOFTWARE | Exact Compose OFF callback dispatches `false`; requested state and TorchCallback changed ON/OFF; 10 UI cycles ended OFF | Requires observable physical LED |
| Vibration | Pixel_10_Pro AVD | PASS_SOFTWARE | Pulse and persistent ON/OFF API results visible; cancellation and remote-conflict regressions pass | Requires tactile confirmation |
| Real BLE discovery | — | NOT_VERIFIED | Transport regressions only | Requires two phones |
| GATT CCCD subscription | — | NOT_VERIFIED | Ordering tests only | Requires two phones |
| 60 BPM notifications | — | NOT_VERIFIED | Mock/simulation excluded | Requires two phones |
| BLE reconnect | — | NOT_VERIFIED | Stale-callback regressions only | Requires two phones |
| VirtualSleepBand background operation | — | NOT_VERIFIED | APK built/installed on AVD | Requires Phone B |
| 90-night import | Pixel_10_Pro AVD | PASS_EMULATOR_UI | Room UI: exactly 90 sessions/summaries | None observed |
| Demo Insights | Pixel_10_Pro AVD | PASS_EMULATOR_UI | Nonempty metrics/trend and 90-session count | None observed |
| Real History preservation | Pixel_10_Pro AVD | PASS_EMULATOR_UI | Reset left exactly 2 real sessions | None observed |
| Performance/endurance | Pixel_10_Pro AVD | PASS_BOUNDED_EMULATOR | 30 min 10 sec mixed-lifecycle run; PSS 135,984 KB; swap 0 | Overnight/physical battery not verified |
| Full regression | Local + Pixel_10_Pro AVD | PASS | 228 JVM, 32 instrumentation, 11 Firebase, 30 Python, dataset verifier; both debug/release builds and lint | Physical gates remain |

Release decision: **GO FOR ONE-TIME FINAL SOFTWARE/DEMO FILMING; NO-GO FOR CLAIMED PHYSICAL ACCEPTANCE**. Software regression and emulator acceptance pass after fixing the TFLite concurrency crash and the actuator control defects. Physical torch light, tactile vibration, affected-device vendor screen recording, physical endurance and two-phone BLE remain unverified.
