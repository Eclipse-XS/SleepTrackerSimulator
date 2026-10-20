# Фінальний звіт з виправлення лабораторних робіт 6 і 7

## Вихідний стан

Гілка `release/labs-1-7-verified`, початковий SHA `c779be381ccd812e8c79ce246d32ebc281709caf`. Незв’язані артефакти лабораторної роботи 5 і локальні QA-файли збережено та не включено до змін.

## Виправлені дефекти

| ID | Severity | Причина | Виправлення | Перевірка |
|---|---|---|---|---|
| ML-01 | P1 | `collectLatest` скасовував активний inference | Окремі ingestion та serial inference coroutines, bounded latest-complete-window channel, явний лічильник dropped windows | 161 JVM tests, build |
| RULE-01 | P1 | `combine` повторно оцінював той самий input | Окремі sensor і prediction events, один serial consumer, стабільний `inputId`, duplicate guard | `SmartRuleEngineTest` |
| EVENT-01 | P0 | Side effect виконувався до Room insert | Execution UUID та `PENDING` row записуються до actuator/BLE; помилка insert забороняє side effect | кодовий порядок, DAO tests |
| HISTORY-01 | P1 | `ON DELETE CASCADE` видаляв audit history | Room v5 з immutable rule snapshot і без foreign-key cascade | migration compilation, DAO instrumentation source |
| CLOUD-01 | P1 | Не було окремої rule-event операції | Versioned `SmartRuleEventPayload`, outbox type, Worker/foreground drain, idempotent Firebase key | outbox tests, Firebase 6/6 |
| BLE-01 | P1 | `ALERT` повертав ACK без зміни device state | `VirtualSleepBand` з `DeviceAlertState`, revision, event ID та duplicate ACK після processing | `MockBleConnectorTest` |
| SENSOR-01 | P2 | `trySend` failure і window drop не спостерігались | Diagnostic dropped-sample counter та exposed dropped-window counter | build, unit tests |

## Архітектура ML

`SensorManager` формує ordered `SensorSample`. `SensorWindowBuffer` приймає лише finite монотонні значення, скидається при non-monotonic timestamp або gap понад 100 ms і копіює завершене вікно. Bounded channel містить не більше одного завершеного вікна. Якщо inference відстає, система навмисно відкидає старі завершені вікна та рахує втрати. Один consumer послідовно викликає TFLite, тому Interpreter не виконується конкурентно. `CancellationException` не перетворюється на UI error.

Контракт моделі: float32 `[1,20,3]` у порядку X, Y, Z; float32 `[1,3]`; labels `REST`, `RESTLESS`, `ACTIVE`. SHA-256: `1CF844F774355E54F820C4860304DC43E42A4015F57C7097CEE9D16227858311`. 97,22% стосується лише held-out synthetic set і не доводить клінічну валідність.

## Життєвий цикл Smart Rule event

1. Новий sensor event оцінює лише числові правила. Новий prediction event оцінює лише ML правила.
2. Engine відкидає повторний `inputId`, серіалізує runtime state і скидає його після редагування правила.
3. ViewModel створює UUID та Room row зі статусом `PENDING`.
4. Після успішного insert виконується vibration або virtual BLE command.
5. Actuator і BLE результати зберігаються окремо.
6. `UPLOAD_RULE_EVENT` потрапляє до Room outbox з operation ID `rule-<eventId>`.
7. Firebase записує `/users/<uid>/sessions/<sessionId>/ruleEvents/<eventId>`.
8. Після remote ACK локальний `cloudStatus` стає `SYNCED`.

## Room і Firebase

Room schema v5 зберігає snapshot назви правила, session ID, sensor timestamp, prediction ID та незалежні actuator, BLE і cloud statuses. Migration 4→5 перебудовує таблицю й переносить старі записи без каскадного видалення. Firebase Rules мають default deny, UID isolation, allowlist полів, bounds і `$other: false`. Emulator перевірив owner write, invalid schema rejection та cross-user denial.

## BLE state machine

`sendAlert(eventId)` дозволений лише у `Connected`. Virtual device спочатку змінює `DeviceAlertState`, збільшує revision і лише після цього повертає ACK з event ID. Повтор тієї самої команди не змінює revision і повертає `ALERT_DUPLICATE_ACK`. Scan, disconnect і close очищають active alert.

## Виконані перевірки

- `gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug compileDebugAndroidTestSources`: PASS.
- JVM: 161 tests, 0 failures, 0 skipped.
- Firebase Emulator: 6 tests, 0 failures.
- `connectedDebugAndroidTest`: BLOCKED, у `adb devices -l` немає підключеного пристрою.
- `git diff --check`: PASS; повідомлення CRLF не є whitespace error.
- Secret-pattern scan staged scope: збігів private keys або access tokens немає.

## Непідтверджені вимоги

- Немає нового correlated device E2E запису для sensor→ML→rule→Room→BLE→Firebase.
- Немає нового release benchmark на фізичному пристрої; попередній debug AVD jank не є production показником.
- Initial Room execution insert і cloud outbox insert виконуються послідовно, а не однією cross-DAO Room transaction. При crash між ними event лишається локально зі статусом `NOT_QUEUED`; автоматичний reconciler для цього стану відсутній.
- Runtime rearm/cooldown не переживає process death. Після restart правило rearmed за явною політикою in-memory runtime state.
- Реальний BLE hardware transport і real-world ML validation відсутні.
- Office DOCX/PDF/PPTX не оновлено в цьому проході, тому старі формулювання в них не можна використовувати як доказ нового стану.

## Оцінка

Lab 6: 91/100. Втрати: synthetic-only data, немає фізичного sampling trace, golden Python/Kotlin probability parity та device lifecycle stress test.

Lab 7: 86/100. Втрати: blocked instrumentation/E2E, немає atomic execution+outbox transaction і recovery для `NOT_QUEUED`, немає physical-device performance proof, Office deliverables не синхронізовані.

Production readiness: NO-GO. Лабораторна реалізація суттєво надійніша, але наведені обмеження несумісні з production claim.
