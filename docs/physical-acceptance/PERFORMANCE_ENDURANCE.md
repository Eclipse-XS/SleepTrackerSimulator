# Performance and endurance

Device: Pixel_10_Pro AVD, Android 16/API 36.

| Test | Observation | Result |
|---|---|---|
| Recording with concurrent screen capture | No crash after fix; session persisted | PASS_EMULATOR |
| 20 Pause/Resume cycles | Active state retained; no crash | PASS_EMULATOR |
| Five minutes background + screen off | Process retained; session completed | PASS_EMULATOR |
| Post-endurance memory | Total PSS 133,685 KB; total RSS 281,068 KB; Java heap 15,288 KB; native heap 17,008 KB | OBSERVED |
| Dropped-sample/session coverage | Final UI reported 100% coverage | PASS_EMULATOR |
| 30-minute run | Not executed on physical hardware | NOT_VERIFIED |
| Battery consumption | Emulator is not valid battery evidence | NOT_VERIFIED |
| BLE notification continuity | No two-phone transport | NOT_VERIFIED |

The five-minute run is a bounded regression, not proof of overnight stability.
