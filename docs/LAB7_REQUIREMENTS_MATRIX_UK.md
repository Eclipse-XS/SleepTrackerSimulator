# Матриця вимог лабораторної роботи №7

Статуси: **PASS** — підтверджено кодом і виконаною перевіркою; **PARTIAL** — реалізація є, але повний ручний сценарій або зовнішній сервіс не підтверджено; **BLOCKED** — середовище не дозволило перевірку.

| ID | Вимога | Джерело | Обов'язковість | Реалізація | Перевірка й доказ | Статус | Залишок |
|---|---|---|---|---|---|---|---|
| R7-01 | Реальні вимірювання смартфона | методика, вхід | обов'язкова | `AndroidAccelerometerDataSource` | AVD, Dashboard | PASS | Дані AVD синтетичні, API справжній |
| R7-02 | Edge AI через TFLite | методика, AI | обов'язкова | `ActivityClassifier`, `MlViewModel` | instrumentation-тести моделі | PASS | Не клінічна модель |
| R7-03 | Локальне зберігання | методика, Room | обов'язкова | `SensorRecord`, `SmartRuleEntity`, `SmartRuleExecutionEntity` | DAO і migration tests | PASS | — |
| R7-04 | Firebase sync | методика, cloud | обов'язкова | `SmartRuleEventPayload`, Room outbox, WorkManager, idempotent event key | Firebase Emulator Suite 6/6 | PASS | Initial execution та outbox inserts ще не atomic |
| R7-05 | Локальний actuator | методика, output | обов'язкова | `AndroidSmartActuator`, `ActuatorViewModel` | unit tests, AVD | PASS | Фізичний ефект в емуляторі обмежений |
| R7-06 | Mock IoT alert | методика, BLE | обов'язкова | `DeviceAlertState`, correlated ACK, duplicate guard | `MockBleConnectorTest` | PASS | Це програмна BLE-імітація |
| R7-07 | ML widget | Dashboard | обов'язкова | `SensorScreen` + `MlUiState` | AVD | PASS | — |
| R7-08 | Останні п'ять записів Room | Dashboard | обов'язкова | `analyticsState.records.takeLast(5)` | build, AVD | PASS | Показуються лише після накопичення даних |
| R7-09 | Cloud status | Dashboard | обов'язкова | `CloudSyncUiState` | unit/Firebase tests | PASS | Реальна хмара не змінювалась |
| R7-10 | BLE status | Dashboard | обов'язкова | `BleUiState` | unit tests, AVD | PASS | — |
| R7-11 | Швидкі actuator controls | Dashboard | обов'язкова | `SensorScreen`, `ActuatorViewModel` | AVD | PASS | Flashlight залежить від пристрою/дозволу |
| R7-12 | Offline queue/recovery | fault tolerance | обов'язкова | transactional Room outbox, Worker | unit + Firebase emulator | PASS | Ручне перемикання мережі не записане у відео |
| R7-13 | False-positive filtering | alert policy | обов'язкова | consecutive matches, rising edge, cooldown | `SmartRuleEngineTest` | PASS | Стан rearm не зберігається між процесами |
| R7-14 | Dependency injection | architecture | рекомендована | constructor/manual factories | JVM tests with fakes | PASS | DI framework навмисно не додано |
| R7-15 | Smart Scenarios | automation | обов'язкова | screen, VM, repository, engine, Room | unit/DAO/migration + AVD screenshot/video | PASS | Повний ручний sensor-threshold цикл не записано |
| R7-16 | Profiling | performance | обов'язкова | adb meminfo/cpuinfo/gfxinfo evidence | `docs/evidence/lab7/profiling/` | PASS | Android Studio GUI Profiler недоступний автоматизації |
| R7-17 | Architecture diagram | report | обов'язкова | Mermaid sources in `docs/diagrams/lab7` | source inspection | PASS | — |
| R7-18 | Business rules | report | обов'язкова | final audit/report | document review | PASS | — |
| R7-19 | Test-case matrix | verification | обов'язкова | final audit | executed command logs | PASS | Частина hardware/cloud manual cases partial |
| R7-20 | Video | deliverable | обов'язкова | попередній `lab7-smart-scenario.mp4` | файл існує, але не доводить новий cloud/BLE lifecycle | BLOCKED | Новий device відсутній |
| R7-21 | Conclusions | report | обов'язкова | DOCX/final audit | document review | PASS | — |
| R7-22 | Exceptional situations | report/tests | обов'язкова | error states, outbox guards, migrations | unit/instrumentation tests | PASS | Disk-full only documented, not destructively reproduced |
