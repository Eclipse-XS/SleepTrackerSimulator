# Two-phone BLE/GATT acceptance

Two physical phones were not connected. The mandatory radio-to-radio path could not be executed.

Both APKs were built from the same working tree and installed on the available AVD, but a single emulator is not evidence for BLE advertising, scanning, CCCD delivery, RF disconnects, or two-phone background operation.

| Stage | Result |
|---|---|
| SleepSense debug APK build/install | PASS_EMULATOR |
| VirtualSleepBand debug APK build/install | PASS_EMULATOR |
| AndroidGattDeviceTransport unit/instrumentation regressions | PASS_AUTOMATED |
| GATT registration ordering/stale callback regressions | PASS_AUTOMATED |
| Physical advertising and discovery | NOT_VERIFIED |
| Physical CCCD acknowledgement | NOT_VERIFIED |
| 60 physical BPM notifications | NOT_VERIFIED |
| Physical disconnect/reconnect | NOT_VERIFIED |
| VirtualSleepBand foreground-service survival on Phone B | NOT_VERIFIED |
| Provenance through a physical GATT session into Room | NOT_VERIFIED |

Required prerequisite: two authorized BLE-capable Android phones, with SleepSense on Phone A and VirtualSleepBand on Phone B.
