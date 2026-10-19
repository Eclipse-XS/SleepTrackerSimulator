# Другий прохід критичного виправлення та верифікації

Дата: 19 жовтня 2026 року.

## 1. Перевірений commit і стан робочого дерева

- Базовий commit: `f1fb5805cefbebaa39fb87cc4ff357a91459ffb5`.
- Гілка: `fix/pre-lab6-reliability`.
- Перевірено фактичне незакомічене робоче дерево, а не лише попередній звіт.
- До другого проходу дерево вже містило зміни першого проходу, змінений користувачем `database.rules.json`, документи у `deliverables/`, QA-артефакти та утиліти у `tools/`. Вони не видалялися, не скидалися і не пушилися.
- Після змін `git diff --check` завершився без помилок. Повідомлення Git про майбутню нормалізацію LF→CRLF не є whitespace error.

## 2. Фактично підтверджені дефекти

| ID | Підтверджений дефект | Конкретний доказ | Результат |
|---|---|---|---|
| P0-01 | Життєвий цикл сенсора міг залежати від довгоживучого ViewModel subscriber | `SensorViewModel` мав розділити foreground-вимогу Activity і наявність downstream collectors | Виправлено; повторні pause/resume та кілька subscribers покриті тестом |
| P0-02 | Atomic JSON без ліміту не був production-grade outbox | `AtomicJsonCloudStateStore` не давав транзакцій Room, контрольованої межі та довговічної retry-метаінформації | Замінено у production wiring на Room outbox v3 |
| P0-03 | Відкладений `END_SESSION` не мав повного доказу process-death recovery | Відновлення залежало від foreground-процесу | Реалізовано durable intent та перевірено process-recreation unit-сценарієм |
| P0-04 | Не було планувальника доставки після завершення процесу/перезапуску пристрою | WorkManager був відсутній | Додано constrained unique work із backoff/retry |
| P0-05 | Відмова camera permission могла пройти суперечливі success/error гілки | callback у `MainActivity` викликав некоректну послідовність | Виправлено; відмова вимикає flashlight і дає явну помилку |
| P0-06 | UID guard існував лише у foreground flow | Фоновий drainer також має перевіряти owner UID | Guard додано у worker; mismatch стає permanent failure |
| RACE-01 | `ExistingWorkPolicy.KEEP` допускав втрату successor scheduling на межі завершення worker | Нову операцію можна було enqueue під час ще активного unique work | Політику змінено на `REPLACE`; UUID-ключі роблять повтор безпечним |
| RACE-02 | Foreground і worker drainers могли одночасно підтвердити один head | Обидва читали один FIFO head | Додано process-wide `Mutex` навколо drain |
| DATA-01 | Помилка persistence не зупиняла producer | Після disk/full-limit failure нові snapshots могли продовжити надходити | Producer скасовується, UI переходить у явний backpressure/error стан |

Повторна перевірка попередніх виправлень підтвердила: BLE session state очищується при scan/connect/disconnect; actuator events зберігаються окремими рядками; XYZ UI використовує фактичні осі; Room 1→2 і 2→3 не застосовують destructive fallback.

## 3. Точні зміни реалізації

- `CloudOutboxPersistence.kt`: таблиці `cloud_outbox` і `cloud_active_session`, FIFO за auto-increment `sequence`, унікальний `operationId`, транзакційні enqueue/dequeue/complete-END, retry count, last error, permanent state, межа 10 000 без eviction та `CloudOutboxFullException`.
- `SleepTrackerDatabase.kt`: schema version 3 і explicit `MIGRATION_2_3`; збережено `MIGRATION_1_2`; додано schema export `3.json`.
- `CloudRecovery.kt`: `CloudOutboxWorker`, `NetworkType.CONNECTED`, exponential backoff 10 секунд, unique work, `REPLACE`, UID guard і спільний mutex drainer-ів.
- `CloudSyncViewModel.kt`: persistence на IO dispatcher, stable UUID operations, durable offline `END_SESSION`, recovery scheduling, owner mismatch/permanent failure, явна зупинка producer при storage failure та захист від async-init/stop race.
- `MainActivity.kt`: production wiring переведено з JSON на Room; підключено WorkManager scheduler; виправлено camera-permission callback.
- `ActuatorViewModel.kt`: явний `reportFlashlightPermissionDenied()`, lifecycle cleanup; deterministic policy: остання foreground-команда діє, початковий remote snapshot не перезаписує вже виконану manual-команду, pause завжди викликає `stopAll()`, resume застосовує актуальний remote state.
- Legacy atomic JSON імпортується транзакційно та ідемпотентно при першому доступі Room і перейменовується лише після успішного import. Пошкоджений файл зберігається, помилка не приховується.
- `database.rules.json` не послаблювався у цьому проході. Правила лишають owner scope, typed validation, `$other: false` та immutable session identity fields.

## 4. Змінені файли

Основні файли другого проходу:

- `app/build.gradle.kts`, `gradle/libs.versions.toml`;
- `app/src/main/java/com/example/sleeptrackersimulator/cloud/CloudModels.kt`;
- `app/src/main/java/com/example/sleeptrackersimulator/cloud/PersistentCloudStateStore.kt`;
- `app/src/main/java/com/example/sleeptrackersimulator/cloud/CloudOutboxPersistence.kt`;
- `app/src/main/java/com/example/sleeptrackersimulator/cloud/CloudRecovery.kt`;
- `app/src/main/java/com/example/sleeptrackersimulator/history/SleepTrackerDatabase.kt`;
- `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/CloudSyncViewModel.kt`;
- `app/src/main/java/com/example/sleeptrackersimulator/ui/dashboard/ActuatorViewModel.kt`;
- `app/src/main/java/com/example/sleeptrackersimulator/app/MainActivity.kt`;
- `app/schemas/com.example.sleeptrackersimulator.history.SleepTrackerDatabase/3.json`;
- `firebase-tests/package.json`, `firebase-tests/package-lock.json`, `firebase-tests/database.rules.test.mjs`;
- `README.md` і цей звіт.

Інші modified/untracked файли у `git status` походять також із першого проходу або існували до другого проходу; цей звіт не приписує їх усі поточному етапу.

## 5. Додані регресійні тести

- `RoomCloudStateStoreTest`: FIFO, транзакційне завершення сесії, явний overflow без видалення старих даних.
- `SleepTrackerMigrationTest`: створення реальної v2 SQLite, вставка sensor/event history, відкриття v3 через обидві migration та перевірка збереження рядків.
- `CloudSyncViewModelTest`: offline measurements + `END_SESSION` + recreation offline + reconnect + ordered delivery + очищення правильної сесії; recovery scheduling; init/stop race.
- `SensorViewModelLifecycleTest`: кілька downstream subscribers, повторні background/foreground transitions, нуль активних listeners у background.
- `ActuatorViewModelTest`: camera permission denial, rapid/lifecycle control regressions разом з наявними manual/remote/automatic tests.
- `database.rules.test.mjs`: owner read/write, unauthenticated/cross-user denial, unknown-field/invalid-HR denial, typed remote controls.

## 6. Виконані команди та результати

| Команда | Результат |
|---|---|
| `.\gradlew.bat testDebugUnitTest` | PASS: 149 tests, 0 failures, 0 errors, 0 skipped |
| `.\gradlew.bat assembleDebug` | PASS |
| `.\gradlew.bat assembleRelease` | PASS |
| `.\gradlew.bat lintDebug` | PASS: 0 issues у XML report |
| `.\gradlew.bat compileDebugAndroidTestSources` | PASS |
| `.\gradlew.bat connectedDebugAndroidTest` | PASS на `Pixel_10_Pro` API 36; включає Room migration/outbox instrumentation |
| `npx firebase-tools@latest emulators:exec --only database "npm --prefix firebase-tests test" --project demo-sleep-tracker-rules` | PASS: 4/4 rules tests на demo emulator, без live credentials |
| `git diff --check` | PASS |

Перший connected-запуск повернув `No connected devices`; після перевірки AVD було завантажено `Pixel_10_Pro`, `sys.boot_completed=1`, і повторний запуск пройшов. Це не лишається blocker-ом.

## 7. Матриця трасованості вимог ЛР1–ЛР5

| Вимога | Фактична реалізація | Тест/доказ | Статус |
|---|---|---|---|
| ЛР1: X/Y/Z | `SensorScreen` показує три осі у m/s² | compile + instrumentation; кодовий огляд | PASS |
| ЛР1: графічний індикатор | signed axis bars із того самого sample | compile + instrumentation | PASS |
| ЛР1: `onPause` unregister | `MainActivity` вимикає foreground collection; data source `awaitClose` знімає listener | lifecycle unit tests з повторними transitions і subscribers | PASS |
| ЛР1: recreation | foreground gate не дорівнює ViewModel lifetime | lifecycle regression tests | PASS |
| ЛР2: selectable discovery list | UI обробляє zero/one/many і вибір за address | compile; connector tests | PASS |
| ЛР2: `sendData(String)` | контракт в `IBleConnector`, реалізація `MockBleConnector` | `MockBleConnectorTest` | PASS |
| ЛР2: reconnect без stale HR | session data очищується на transition | `MockBleConnectorTest` | PASS |
| ЛР3: 5-second sync | `CLOUD_SYNC_INTERVAL_MS = 5000` | `CloudSyncViewModelTest` | PASS |
| ЛР3: Pending status | UI показує queue count/status; Room outbox durable | unit + Room instrumentation | PASS |
| ЛР3: offline END_SESSION/process death | durable ordered operation + WorkManager recovery | recreation unit scenario + outbox instrumentation | PASS |
| ЛР3: Firebase owner security | UID guard у foreground/worker; owner-scoped rules | Firebase Database Emulator 4/4 | PASS |
| ЛР4: `DecisionEngine` | pure edge detector, rearm, no repeated spam | `DecisionEngineTest` | PASS |
| ЛР4: manual/remote controls | typed commands, deterministic foreground conflict policy | `ActuatorViewModelTest` | PASS |
| ЛР4: pause safety | `stopAll()` припиняє repeat vibration/torch | lifecycle tests і кодовий огляд | PASS |
| ЛР4: camera denial | explicit off/error path | regression unit test | PASS |
| ЛР5: Room Entity/DAO/MVVM | sensor records + typed actuator events + repositories/ViewModels | unit та instrumentation DAO tests | PASS |
| ЛР5: non-destructive migration | explicit 1→2→3, schema exports | реальний SQLite migration test на AVD | PASS |
| ЛР5: reactive LineChart | Room `Flow`, newest 50 у chronological order, adaptive scale | DAO/instrumentation + chart scale unit tests | PASS |
| Діаграми у методичному звіті | Наявні deliverables і diagram assets | Файли присутні, зміст не є runtime-доказом | PASS з обмеженням |
| Актуальні screenshots після всіх pass-2 змін | Автоматично не сфабриковані | Немає підтвердженого комплекту post-change screenshots | BLOCKED |
| Фізичний BLE hardware | Поточний transport — симулятор | Фізичний пристрій не тестувався | BLOCKED, якщо методика вимагає hardware |

## 8. Залишкові блокери та ризики

- Немає підтвердженого набору ручних screenshots саме після другого проходу. Автоматична збірка та AVD-тести цього не замінюють.
- Фізичний BLE transport не реалізований: лабораторний сценарій використовує `MockBleConnector`/Virtual Sleep Band.
- Android force-stop блокує scheduled background execution до наступного явного запуску користувачем; WorkManager не може обійти системне обмеження.
- Outbox контрольовано обмежений 10 000 операцій і переходить у backpressure. Це унеможливлює тиху втрату, але вимагає операційного UI/алерту та політики звільнення місця для production.
- Немає user-facing export/delete flow для локальної та хмарної історії.
- Release minification/obfuscation, Play Console pre-launch, accessibility, battery profiling, Data Safety і довготривале soak-тестування не виконані.
- Порогові значення sleep/rest демонстраційні, не медично валідовані.
- Firebase test dependencies встановилися з engine warnings для Node 22 та `npm audit` повідомив 5 high vulnerabilities у test-only dependency tree. Emulator tests пройшли, але toolchain слід оновити до підтримуваної Node версії.

## 9. Оцінка лабораторної відповідності

**94/100.** Обов’язкові програмні контракти ЛР1–ЛР5 реалізовані та автоматично перевірені, включно з AVD/SQLite і Firebase Emulator. Знято бали за відсутність актуального ручного screenshot-пакета та фізичної BLE-перевірки.

## 10. Оцінка production readiness

**82/100.** Критичні persistence, ordering, lifecycle, UID, migration і deferred-delivery дефекти закриті. Production статус не надається через відсутність release hardening, user-data controls, soak/battery/accessibility перевірок, фізичного transport та operational handling переповненого outbox.

## 11. Зважена оцінка

`0.70 × 94 + 0.30 × 82 = 90.4/100`.

## 12. Вердикт GO/NO-GO

- **Лабораторний захист: GO з документованими обмеженнями.** Код, unit, build, lint, AVD instrumentation, Room migration та Firebase rules emulator мають фактичний PASS. Перед формальним захистом бракує лише актуальних ручних screenshots і, якщо методика буквально вимагає, фізичного BLE evidence.
- **Production deployment: NO-GO.** Оцінка 82/100 не скасовує незакриті release, privacy, operational і hardware validation вимоги.
