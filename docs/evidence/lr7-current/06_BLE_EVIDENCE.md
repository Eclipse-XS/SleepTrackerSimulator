# BLE evidence

`:app` містить `DeviceTransport`, `MockDeviceTransport`, `AndroidGattDeviceTransport` і in-app advertiser. `:virtualsleepband` містить `VirtualGattServer`, advertising, notifications, battery/heart-rate data and command handling.

Підтверджені UUID: Heart Rate Service 0x180D, Heart Rate Measurement 0x2A37, Body Sensor Location 0x2A38, Battery Service 0x180F, Battery Level 0x2A19; власні command/ACK UUID визначені у протокольному коді.

- Mock/protocol logic: SIMULATED / MOCK VERIFIED JVM tests.
- GATT client/server structure: SOURCE VERIFIED.
- Companion install and launch: EMULATOR VERIFIED; screenshot `18_virtual_sleep_band.png`.
- Advertising на AVD: не є доказом радіоканалу.
- PHYSICAL BLE E2E: NOT VERIFIED; потрібні два сумісні фізичні пристрої.

Компілятор повідомляє deprecated Java BLE overloads у central і peripheral. Вони збираються, але потребують подальшої API-modernization перевірки.
