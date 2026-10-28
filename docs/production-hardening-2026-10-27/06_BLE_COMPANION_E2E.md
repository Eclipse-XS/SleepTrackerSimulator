# BLE companion E2E

The repository has an Android GATT client and a separate peripheral application, but the main Devices screen is wired to `MockBleConnector`. The recording service separately receives `MockDeviceTransport`. Therefore the current consumer flow does not satisfy Real BLE Mode.

The companion advertises Heart Rate and Battery services, but is Activity-bound, accepts subscriptions without tracking CCCD state, and emits manually controlled jitter. The client reports `CONNECTED` before service discovery/subscription completes and labels all packets `BLE_REAL`.

No two-device test or 60-packet capture was executed. Status: `NOT_FIXED`; physical E2E: `BLOCKED`.
