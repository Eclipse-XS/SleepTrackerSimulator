# Demo dataset UI acceptance

Device: Pixel_10_Pro AVD, Android 16/API 36.

The installed application was operated through its Compose UI, not only through a DAO test.

| Procedure | Actual outcome | Result |
|---|---|---|
| Developer Lab → Room → Generate | Generated 90 nights | PASS_EMULATOR_UI |
| Import and Verify | `Room integrity verified: 90 synthetic sessions and summaries` | PASS_EMULATOR_UI |
| History → Demo | `All (90)` and `SIMULATED` provenance | PASS_EMULATOR_UI |
| Insights → Demo | 90 sessions; 7h46m average; 80% low-motion; 15.6 episodes/night; 91% coverage; BPM 61 | PASS_EMULATOR_UI |
| Real Insights isolation | Two real sessions shown separately with descriptive metrics and no baseline | PASS_EMULATOR_UI |
| Force-stop/relaunch | History retained `All (92)` (90 demo + 2 real) | PASS_EMULATOR_UI |
| Generate/import again | Verify remained exactly 90 demo sessions/summaries | PASS_EMULATOR_UI |
| Reset demo | History became `All (2)`; real sessions remained | PASS_EMULATOR_UI |

Evidence:

- [Room verification](evidence/room-90-night-verified.png)
- [Demo History](evidence/history-demo-90.png)
- [Demo Insights](evidence/insights-demo-90.png)
- [History after reset](evidence/history-after-demo-reset.png)
