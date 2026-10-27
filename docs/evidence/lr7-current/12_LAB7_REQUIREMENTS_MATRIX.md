# Lab 7 requirements matrix

| Requirement | Code location | Runtime evidence | Test evidence | Status |
|---|---|---|---|---|
| Unified Labs 1–6 system | AppContainer + Developer Lab | screenshots 10–17 | full regression | VERIFIED |
| Sensor acquisition | RecordingService | 02 recording, 11 sensor | DataFlow E2E | EMULATOR VERIFIED |
| Local ML | ActivityClassifier | 16 ML | 4 Android ML tests | VERIFIED |
| Rule behavior | SmartRuleEngine | 09 automations | SmartRuleEngineTest | VERIFIED |
| Local storage | Room v7 | 15 Room | migration/DAO tests | VERIFIED |
| Cloud sync | cloud package | 13 Cloud | Rules + Cloud E2E | EMULATOR VERIFIED |
| BLE | data/ble + companion | 08, 12, 18 | protocol tests | MOCK/STRUCTURE VERIFIED; physical NOT VERIFIED |
| Actuator | actuator + smart VM | 14 Actuator | JVM/E2E | EMULATOR VERIFIED |
| Scenario management | SmartScenariosScreen | 09 automations | DAO test | VERIFIED |
| Offline behavior | Room outbox | Today LOCAL ONLY | CloudOfflineE2E | VERIFIED |
| Fault recovery | coordinator + event coordinator | documented UI | recovery tests | VERIFIED |
| Integration tests | androidTest/e2e | n/a | 22/22 | VERIFIED |
| Real runtime evidence | product screens | PNG set | install/launch | EMULATOR VERIFIED |
| Limitations | this package | n/a | n/a | DOCUMENTED |
