# Final physical acceptance matrix

| Test | Device | Result | Evidence | Remaining issue |
|---|---|---|---|---|
| Recording without screen recorder | Pixel_10_Pro AVD | PASS_EMULATOR | Completed summaries with 100% coverage | Physical phone not tested |
| Recording with screen recorder | Pixel_10_Pro AVD | PASS_EMULATOR_AFTER_FIX | Native crash reproduced, fixed, and absent on retest | Vendor recorder/affected phone unavailable |
| 30-minute recording | — | NOT_VERIFIED | — | Requires physical device and elapsed run |
| Flashlight ON/OFF | — | NOT_VERIFIED | Automated state tests only | Requires observable physical LED |
| Vibration | — | NOT_VERIFIED | Automated state tests only | Requires tactile confirmation |
| Real BLE discovery | — | NOT_VERIFIED | Transport regressions only | Requires two phones |
| GATT CCCD subscription | — | NOT_VERIFIED | Ordering tests only | Requires two phones |
| 60 BPM notifications | — | NOT_VERIFIED | Mock/simulation excluded | Requires two phones |
| BLE reconnect | — | NOT_VERIFIED | Stale-callback regressions only | Requires two phones |
| VirtualSleepBand background operation | — | NOT_VERIFIED | APK built/installed on AVD | Requires Phone B |
| 90-night import | Pixel_10_Pro AVD | PASS_EMULATOR_UI | Room UI: exactly 90 sessions/summaries | None observed |
| Demo Insights | Pixel_10_Pro AVD | PASS_EMULATOR_UI | Nonempty metrics/trend and 90-session count | None observed |
| Real History preservation | Pixel_10_Pro AVD | PASS_EMULATOR_UI | Reset left exactly 2 real sessions | None observed |
| Performance/endurance | Pixel_10_Pro AVD | PASS_BOUNDED_EMULATOR | 5-minute background/screen-off run | Overnight/physical battery not verified |
| Full regression | Local + Pixel_10_Pro AVD | PASS | 223 JVM, 28 instrumentation, 11 Firebase, 8 Python | Physical gates remain |

Release decision: **NO-GO FOR CLAIMED PHYSICAL ACCEPTANCE**. Software regression and emulator acceptance pass after fixing a real native TFLite concurrency crash, but torch, vibration, affected-device vendor screen recording, 30-minute physical endurance, and two-phone BLE remain unverified.
