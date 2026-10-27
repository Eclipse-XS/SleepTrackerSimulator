# Evidence limitations

- Working tree dirty; commit SHA alone does not reproduce every generated document/result.
- Physical BLE E2E on two phones: NOT VERIFIED.
- Companion advertising screen is emulator runtime, not proof of over-the-air advertising.
- Production Firebase, real authentication accounts and WAN delivery: NOT VERIFIED; local emulator only.
- ML evaluation dataset is synthetic. Real-human accuracy, clinical validity and sleep-stage detection: NOT VERIFIED and not claimed.
- Performance is one short debug AVD snapshot, not a release benchmark. Battery drain and OEM background behavior: NOT VERIFIED.
- Short forensic session rounds to 0 minutes in History; it proves flow/persistence, not long-duration stability.
- No continuous screen recording was captured.
- Android instrumentation was executed directly because Gradle connected runner had previously produced unreliable empty reports. The direct runner result is retained as the proof.
- Deprecated Android BLE API warnings remain; build and tests pass.
- Screenshots named `09_smart_scenarios.png` and `10_devlab_*` through `17_virtual_sleep_band.png` pre-existed this exact collection sequence. The canonical files for this run are `01_today_idle.png` through `18_virtual_sleep_band.png` using the names listed in `00_MANIFEST.md`.
