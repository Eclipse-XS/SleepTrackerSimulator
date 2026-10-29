# Hardware and configuration limitations

- No physical Android device was attached.
- No second BLE-capable Android device or wearable was available.
- Physical accelerometer placement, screen-off survival, OEM battery restrictions, vibration, flashlight, BLE ALERT, reconnect, and 8-hour overnight behavior were not tested.
- Firebase delivery was not exercised against production configuration; the observed offline outbox behavior was retained locally.
- Emulator BLE advertising and scan startup do not prove over-the-air interoperability.
- No real-subject dataset or clinical ground truth was collected in this run.
