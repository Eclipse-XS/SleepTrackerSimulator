# BLE acceptance — 2026-10-29

VirtualSleepBand status: `VERIFIED_EMULATOR` for local GATT server and advertising startup.

- GATT server registered successfully.
- Heart Rate Service `0x180D` and Battery Service `0x180F` were added with status 0.
- BLE advertiser entered `ADVERTISING` state.
- Simulated BPM changed from 68 to 70.
- Foreground service startup was accepted by Android API 36.
- SleepSense BLE scan registered and started after runtime permission grant.

Cross-device discovery, connection, CCCD subscription, HR notification persistence, disconnect fallback, reconnect, and custom ALERT write are `BLOCKED_HARDWARE`. A single emulator uses one virtual Bluetooth controller and did not discover its own advertisement. This is not counted as physical BLE E2E.

Screenshot: `screenshots/virtual_band_advertising.png`.
