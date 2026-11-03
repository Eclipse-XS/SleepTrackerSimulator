# Independent VirtualSleepBand

VirtualSleepBand is a separate APK and is the only generator of virtual HR. WebSocket is used for deterministic inter-process testing and is not BLE. Physical BLE remains implemented by the GATT server/client pair and requires separate BLE-capable hardware for credible verification.

## Launch and route

Build/install both APKs. Start VirtualSleepBand and press its start control. Its foreground service starts BLE advertising and a WebSocket server on TCP 8765. SleepSense virtual mode connects to `ws://10.0.2.2:8765`.

For two emulators, `10.0.2.2` reaches the host, not the other emulator. Forward host port 8765 to the VirtualSleepBand emulator:

```text
adb -s <virtual-band-emulator> forward tcp:8765 tcp:8765
```

Then run SleepSense on the other emulator, select `Virtual · WebSocket`, scan, and connect to the candidate. Stopping or force-stopping VirtualSleepBand closes or times out the session; the HR state becomes unavailable while phone monitoring remains operational. Restart and reconnect without restarting SleepSense.

The endpoint defaults to `ws://10.0.2.2:8765` and is read from SharedPreferences file `wearable_transport`, key `virtual_websocket_endpoint`. A production UI for editing and validating this value is not implemented. The server rejects clients that do not identify the `SLEEPSENSE` role, but it does not yet provide cryptographic pairing or TLS; use only an isolated test network or adb forwarding.

Messages use protocol version 1 with session/device/message identity, sequence, UTC timestamp, event type, and JSON payload. `ACTUATOR_COMMAND` receives `COMMAND_ACK` followed by `COMMAND_RESULT`; execution is explicitly `SIMULATED_EXECUTION`.

This path verifies independent-process transport only. It does not verify BLE advertising, GATT discovery, CCCD notifications, radio behavior, physical HR sensing, or physical actuation.

