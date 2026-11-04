# Independent VirtualSleepBand

`virtualsleepband/` builds an independently installable APK. It generates **synthetic** heart-rate samples and exposes an RFC 6455 WebSocket server on TCP port `8765`. It also implements a BLE GATT peripheral for testing with compatible separate physical Android devices. These transports are not equivalent.

## WebSocket connection

1. Install and start the VirtualSleepBand APK; activate its server.
2. Open SleepSense → Devices and select the virtual WebSocket transport.
3. Use the endpoint editor (`DevicesViewModel.saveVirtualEndpoint()`) and connect.
4. Verify a fresh device-info handshake and HR stream; disconnect the companion and verify fallback to accelerometer-only operation.

**Same Android emulator:** both APKs use one guest network namespace; `ws://127.0.0.1:8765` reaches the companion process.

**Separate Android emulators:** the emulator address `10.0.2.2` means *host computer*, not the peer emulator. Port-forward host port 8765 to the emulator running VirtualSleepBand:

```bash
adb -s <virtual-band-emulator-serial> forward tcp:8765 tcp:8765
```

Then the other emulator can connect to `ws://10.0.2.2:8765` via the host. Check the port is not already in use, and remove forwarding after testing.

## Contract and limitations

The wire contract is implemented in `wearable-protocol/src/main/kotlin/com/example/wearableprotocol/WearableProtocol.kt` and is included in both Android app source sets. WebSocket HR has `WEBSOCKET_SIMULATED` provenance; physical BLE is handled by `AndroidGattDeviceTransport`. Missing, stale or invalid HR must not be fabricated as a real signal.

The endpoint validator only accepts `ws://` with a host and an explicit valid port. There is no TLS or cryptographic device pairing; use a trusted isolated development network. BLE advertisements, GATT discovery, CCCD notifications and radio reliability require separate physical-device acceptance tests.
