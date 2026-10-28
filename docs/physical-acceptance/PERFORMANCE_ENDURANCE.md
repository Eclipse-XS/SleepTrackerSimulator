# Performance and endurance

Device: Pixel_10_Pro AVD, Android 16/API 36.

| Test | Observation | Result |
|---|---|---|
| Recording with concurrent screen capture | No crash after fix; session persisted | PASS_EMULATOR |
| 20 Pause/Resume cycles | Active state retained; no crash | PASS_EMULATOR |
| Five minutes background + screen off | Process retained; session completed | PASS_EMULATOR |
| 30-minute mixed-lifecycle run | 30 min 10 sec: foreground with 180 sec Android screen recording, Activity recreation, 10 min background/screen-off, 10 min foreground, 7 min second screen-off | PASS_EMULATOR |
| Process continuity | PID 27097 at start, after screen recording, after recreation, after background and at finish | PASS_EMULATOR |
| Post-endurance memory | Total PSS 135,984 KB; total RSS 283,884 KB; Java heap 16,824 KB; native heap 16,916 KB; swap 0 KB | OBSERVED |
| Dropped-sample/session coverage | Final UI reported 100% coverage | PASS_EMULATOR |
| Crash/ANR evidence | Crash buffer empty; no process death observed | PASS_EMULATOR |
| 30-minute physical-phone run | Not executed on physical hardware | NOT_VERIFIED |
| Battery consumption | Emulator is not valid battery evidence | NOT_VERIFIED |
| BLE notification continuity | No two-phone transport | NOT_VERIFIED |

The 30-minute emulator run is a bounded regression, not proof of overnight stability or physical-device battery behavior. Evidence: `docs/acceptance/evidence/rc-endurance-summary.png`; concurrent recorder output existed at `/sdcard/sleepsense-rc-endurance.mp4` with 507,387 bytes.
