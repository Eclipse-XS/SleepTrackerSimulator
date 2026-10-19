# Технічний звіт про аудит і виправлення

Дата аудиту: 18 жовтня 2026 року.

## 1. Baseline

- Commit: `f1fb5805cefbebaa39fb87cc4ff357a91459ffb5`.
- Гілка: `fix/pre-lab6-reliability`, а не заявлена у завданні `main`.
- До аудиту робоче дерево вже містило змінений `database.rules.json` та незакомічені звіти/QA-артефакти. Вони не видалялися й не перезаписувалися.
- Конфігурація: AGP 9.4.1, Kotlin 2.3.21, compile/target SDK 37, min SDK 31, Room 2.8.5, Firebase BoM 34.19.0.
- Початковий unit-запуск перетнувся зі зміною DAO і закономірно отримав застарілу KSP-реалізацію. Повторний чистий інкрементальний прохід з актуальними джерелами успішний.

## 2. Підтверджені дефекти та корекції

| ID | Пріоритет | Доказ / причина | Корекція | Регресійне покриття | Статус |
|---|---|---|---|---|---|
| DATA-01 | P0 | `AtomicJsonCloudStateStore.enqueue()` видаляв найстарішу не підтверджену телеметрію після 50 записів | Прибрано truncation; legacy `capacity` більше не є політикою втрати; FIFO зберігається до acknowledgement | 3, 51 і 100+ offline operations | FIXED |
| UI-01 | P1 | XYZ були навмисно приховані; README це прямо визнавав | Додано X/Y/Z, одиниці, intensity/state/error та три signed bars із фактичного sample | Production compile; ручний TC-01 потребує емулятора | FIXED, manual evidence pending |
| BLE-01 | P1 | UI викликав `devices.first()` | Список пристроїв, zero/one/many handling, stable address у рядку, повторний scan | Production compile | FIXED |
| BLE-02 | P1 | Поточний HR та history не очищалися при disconnect/reconnect | Session state очищується на scan/connect/disconnect/close; measurement має monotonic timestamp | `MockBleConnectorTest` | FIXED |
| BLE-03 | P2 | Не було лабораторного `sendData(String)`; write UUID був неявним | Додано compatibility method і окремий custom UUID; SIG UUID не використовується неправдиво | `MockBleConnectorTest` | FIXED |
| ROOM-01 | P1 | `pendingActivation: Boolean` стискав кілька actuator events | Додано `actuator_events`, UUID, timestamp, action, source, requested state, result; `SensorRecord.actionTriggered` збережено | Unit та DAO instrumentation source | FIXED |
| ROOM-02 | P1 | Room schema v1 не мала міграції для event history | Version 2, schema export `2.json`, explicit `MIGRATION_1_2`, без destructive fallback | AndroidTest compilation; device execution blocked | FIXED, runtime migration test pending |
| ROOM-03 | P2 | Retention DAO існував, але runtime його не запускав | 30-денне очищення sensor/event rows раз на добу; recent records не зачіпаються | Production compile | FIXED |
| SEC-01 | P1 | Mock BLE вимагав runtime Bluetooth/location permissions | Видалено всі Bluetooth/location permissions | Lint/manifest merge | FIXED |
| SEC-02 | P1 | Backup був увімкнений із template rules | Backup і device transfer вимкнено; усі домени явно виключені | Lint/manifest merge | FIXED |
| SENSOR-01 | P1 | NaN/Infinity/невалідний timestamp могли потрапити в classifier | Валідація sample до classifier, reset і явний error state | Unit compile; окремий boundary test ще потрібний | FIXED |

## 3. Зміни за лабораторними роботами

### ЛР1

XYZ та графічний індикатор інтегровані у dashboard для connected і disconnected BLE станів. Наявна foreground policy лишилася: `onResume()` активує, `onPause()` деактивує sensor pipeline; `awaitClose` знімає listener. Невалідний або відсутній sensor більше не показується як `STILL`.

### ЛР2

Збережено Scan/Connect/Disconnect/Read/Write/Notify. Device discovery став списком. Додано `sendData`. Визначено custom command characteristic. HR session state не переживає disconnect як нібито свіже вимірювання.

### ЛР3

Прибрано підтверджену втрату після 50 measurements. Stable IDs, FIFO ordering, idempotent Firebase keys, END_SESSION ordering та owner mismatch guard збережені. Поточний outbox лишився atomic JSON, не Room. Це компроміс: він crash-persistent та atomic, але не підходить для необмеженого production growth. WorkManager не реалізовано; recovery працює після запуску застосунку та доки процес живий.

### ЛР4

Наявні edge-trigger, manual/remote control та cleanup на pause збережені. Event history тепер не стискає кілька успішних activation events. Remote listener може залишатися підписаним у живому ViewModel у background, але hardware commands не застосовуються, а `stopAll()` викликається на pause.

### ЛР5

Обов'язкові поля `SensorRecord` збережені. Room піднято до v2 з explicit migration та новою event table. Analytics action count тепер рахує повні typed events, а не boolean flags sensor rows. Додано runtime retention.

## 4. Фінальний потік даних

```text
SensorManager → AccelerometerDataSource → MovementClassifier → SensorViewModel
                                                       ├→ Room sensor_records
                                                       ├→ DecisionEngine → SmartActuator
                                                       │                    └→ Room actuator_events
                                                       └→ atomic durable outbox → Firebase RTDB

VirtualSleepBand → MockBleConnector → fresh HR session → RestStateClassifier
```

Outbox не є частиною Room у цій ревізії. Тому спрощений ланцюг `Room → Durable Outbox` не описує фактичну атомарну транзакцію: локальна історія та cloud outbox є двома незалежними джерелами з різними контрактами.

## 5. Автоматизована перевірка

| Команда | Фактичний результат |
|---|---|
| `.\gradlew.bat testDebugUnitTest` | PASS: 145 tests, 0 failures, 0 skipped |
| `.\gradlew.bat assembleDebug` | PASS |
| `.\gradlew.bat lintDebug` | PASS; лише dependency-update warnings |
| `.\gradlew.bat compileDebugAndroidTestSources` | PASS |
| `.\gradlew.bat connectedDebugAndroidTest` | BLOCKED: `No connected devices!` |
| Firebase Emulator Suite | BLOCKED: команда `firebase` відсутня |
| `.\gradlew.bat assembleRelease` | PASS; unsigned release artifact не є Play acceptance |

## 6. Ручні сценарії

TC-01–TC-18 не позначено PASS без реального виконання. TC-01, TC-02, TC-03, TC-04, TC-11, TC-12, TC-13, TC-14 і TC-18 потребують Android emulator/device. TC-05, TC-08, TC-10 і TC-16 додатково потребують Firebase configuration/emulators. TC-17 потребує довготривалого профілювання. Кодова та unit-перевірка не замінює ці докази.

## 7. Залишкові обмеження

- Немає WorkManager recovery після process death/reboot; після Android force-stop автоматичне виконання взагалі не гарантується платформою.
- Atomic JSON outbox тепер не втрачає записи, але може необмежено рости. На disk-full він явно повертає storage error, проте немає production backpressure policy.
- Немає UI для підтвердженого очищення local history та видалення cloud history.
- Немає виконаних Firebase rules emulator tests у цьому середовищі.
- Міграція 1→2 скомпільована і schema export створений, але device migration test не виконаний.
- Release minification вимкнена; Play Console, accessibility та data-safety review не виконувалися.
- Physical BLE не реалізований і не тестувався. Поточний transport є simulation.
- Rest-state thresholds демонстраційні, не фізіологічні й не медично валідовані.
- Реальні screenshot/manual evidence після цих UI-змін відсутні.

## 8. Оцінки та вердикт

- Laboratory compliance: **84/100**. Основні ЛР1–5 flows і конкретні методичні контракти присутні; бракує виконаної instrumentation/Firebase/manual evidence та user data controls.
- Production readiness: **58/100**. Усунено silent loss, stale HR, unsafe backup/permissions та event collapsing, але WorkManager, scalable outbox, emulator security tests, release hardening і destructive data controls відсутні.
- Final Score: `0.70 × 84 + 0.30 × 58 = 76.2/100`.

Вердикт: **READY FOR LAB DEFENSE WITH LIMITATIONS**.

Production verdict: **PRODUCTION NO-GO**.
