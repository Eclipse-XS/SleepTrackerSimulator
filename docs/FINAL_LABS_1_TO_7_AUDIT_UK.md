# Фінальний інженерний аудит лабораторних робіт 1–7

Дата: 19 жовтня 2026 року.

## 1. Baseline and repository state

- Гілка: `fix/pre-lab6-reliability`.
- Базовий commit: `f1fb5805cefbebaa39fb87cc4ff357a91459ffb5`.
- Робоче дерево до цього проходу вже було суттєво змінене remediation-проходами ЛР1–5. Зміни не скидалися і не пушилися.
- Вільне місце на C: перед ML-роботою: 75,5 ГБ.
- Android SDK: `C:\Users\Eclipse\AppData\Local\Android\Sdk`; AVD: `Pixel_10_Pro` API 36.
- Gradle 9.6, AGP 9.4.1, Kotlin 2.3.21. Firebase CLI запускався з Android Studio JBR 25, оскільки системна Java 11 не підтримується поточною CLI.
- Повної окремої методички у repository не знайдено; джерелом вимог були надані тексти та попередні remediation reports.

## 2. Remaining Labs 1–5 defects

Попередні шість втрачених балів не відповідали шести дефектам коду. Звіт прямо віднімав їх за відсутність актуального screenshot-пакета та фізичного BLE evidence. Функціональні remediation-зміни ЛР1–5 повторно скомпільовані й протестовані.

Підтверджено один новий дефект outbox policy: черга з 10 000 telemetry operations блокувала `END_SESSION`, хоча користувач повинен мати можливість завершити сесію. Тепер повна telemetry queue дозволяє рівно одну унікальну terminal operation для сесії. Повторна terminal operation і нова telemetry відхиляються без eviction.

## 3. Laboratory 6 implementation

### ML task and data

Реалізовано класи `REST`, `RESTLESS`, `ACTIVE`. Вхід — 20 послідовних XYZ samples. Скрипт `ml/train_activity_model.py` з seed 4319 генерує 3600 train і 900 окремих test-вікон синтетичних сигналів. Синтетичне походження даних є явним; медична валідність не заявляється.

### Model and evaluation

- TensorFlow 2.20.0.
- Input: `float32[1,20,3]`.
- Mean centering за часовою віссю.
- Flatten → Dense 32 ReLU → Dense 3 Softmax.
- Output: `float32[1,3]`.
- Test loss: 0,1771389842.
- Test accuracy: 0,972222209.
- Confusion matrix: `[[300,0,0],[18,282,0],[0,7,293]]`.
- Float TFLite size: 11 368 bytes.

Перший optimized export створив `FULLY_CONNECTED` op v12, яку Android TFLite 2.16.1 не виконував. Instrumentation test виявив це. Export переведено на сумісний float graph; повторний real-device interpreter test пройшов.

### Android inference

`ActivityClassifier` перевіряє labels та tensor shapes, виконує справжній `Interpreter.run()` і вимірює latency. `SensorWindowBuffer` реалізує rolling window. `MlViewModel` виконує inference на `Dispatchers.Default`, не у Compose recomposition, і закриває interpreter у `onCleared()`.

### Integration

Dashboard має картку AI Insights. Перехід у `ACTIVE` з confidence ≥ 0,75 у foreground і при ввімкненій автоматиці створює один vibration pulse. Typed actuator event потрапляє до Room. Окрема таблиця ML predictions не додавалася; прогноз є live state, а історія ефектів зберігається як actuator events.

## 4. Laboratory 7 implementation

Підтверджений pipeline:

```text
SensorManager → SensorViewModel → 20-sample buffer → TFLite → AI Insights
       ├→ Room sensor history
       ├→ Room cloud outbox → WorkManager → Firebase
       └→ ML ACTIVE → actuator → Room actuator event

VirtualSleepBand → MockBleConnector → RestStateClassifier → Dashboard/cloud snapshot
```

Dashboard об’єднує XYZ, movement state, AI Insights, BLE, HR, rest estimate, cloud state та quick actuator controls. Manual DI лишається допустимим навчальним варіантом.

Не реалізовано окремий user-configurable Smart Scenario editor із persisted rules і conflict-priority UI. Поточні правила конфігуруються лише частково: automatic control можна ввімкнути або вимкнути, а ML confidence і movement thresholds є constants. Це не відповідає повній вимозі ЛР7.

## 5. Architecture and lifecycle

- Activity-scoped: foreground gates і Compose navigation.
- ViewModel-scoped: Sensor, BLE, rest, ML, cloud, actuator, recording, analytics owners.
- Application-scoped: Room singleton і Android system services через application context.
- Worker-scoped: `CloudOutboxWorker`.
- Sensor listener скасовується в background через foreground StateFlow та `awaitClose`.
- Interpreter завантажується один раз на MlViewModel і закривається.
- Foreground і worker outbox drainers мають process-wide mutex.

## 6. Testing

| Команда | Фактичний результат |
|---|---|
| `testDebugUnitTest` | PASS: 151 tests |
| `assembleDebug` | PASS |
| `assembleRelease` | PASS, unsigned і без production hardening |
| `lintDebug` | PASS |
| `compileDebugAndroidTestSources` | PASS |
| `connectedDebugAndroidTest` | PASS: 9 tests на Pixel_10_Pro API 36 |
| Firebase Emulator rules | PASS: 4/4 |
| ML training/export | PASS: реальний TensorFlow run і TFLite verification |
| `git diff --check` | PASS; лише LF→CRLF warnings |

Instrumentation охоплює Room migration, DAO/outbox і реальний TFLite inference. Компонентні PASS не є доказом одного автоматизованого повного sensor-to-cloud E2E сценарію. Повний UI E2E, network toggle, BLE command і actuator в одному тесті не реалізовано.

## 7. Performance

- Model file: 11 368 bytes.
- UI показує фактичний per-inference elapsed time.
- Inference виконується не на Main dispatcher.
- P95 latency, heap delta, CPU, battery та sustained profiler interval не виміряні. Відсутність crash не є доказом відсутності leak.
- Retention: 30 днів для local sensor/event history; outbox не evict-ить unacknowledged operations.

## 8. Security

- Firebase owner UID rules і typed field validation пройшли 4 emulator tests.
- Worker має UID mismatch guard.
- Backup і device transfer вимкнені.
- Mock BLE не запитує location/Bluetooth permissions.
- Payload values і Firebase secrets не логуються.
- Немає user-facing export/delete flow, Play Data Safety review та release obfuscation.

## 9. Deliverables

- Model: `app/src/main/assets/activity_classifier.tflite`.
- Labels: `app/src/main/assets/activity_labels.txt`.
- Training: `ml/train_activity_model.py`, `ml/requirements.txt`.
- Evaluation: `docs/ml_evaluation.json`.
- Android ML: `app/src/main/java/com/example/sleeptrackersimulator/ml/`.
- Unit/instrumentation tests: `app/src/test/.../ml/`, `app/src/androidTest/.../ml/`.
- Architecture sources: `docs/architecture_labs_1_7.mmd`, `docs/e2e_sequence_labs_7.mmd`.
- Reports: `deliverables/ЛР-6_ПМСС_Гончаров_Вадим_ОІ-43.docx`, `deliverables/ЛР-7_ПМСС_Гончаров_Вадим_ОІ-43.docx`.
- Defense guide: `docs/DEFENSE_GUIDE_LABS_6_7_UK.md`.
- Genuine emulator evidence: `docs/evidence/sleeptracker-dashboard.png`, `docs/evidence/sleeptracker-ai-insights.png`.

## 10. Remaining limitations

- Smart Scenario editor/persistence/conflict resolution: FAIL.
- Full integrated UI E2E with real network toggle: NOT VERIFIED.
- Android Profiler soak: BLOCKED / not executed.
- Post-change evidence містить dashboard та AI Insights REST; повний набір різних класів, BLE і failure states неповний.
- Final video: BLOCKED / not recorded.
- Final presentation: not produced.
- Physical BLE: not applicable to the selected Mock BLE route, but no hardware evidence exists.
- Dataset is synthetic and small; no field generalization claim is valid.

## 11. Laboratory compliance

| ЛР | Вага | Бал | Причина втрати |
|---|---:|---:|---|
| 1 | 10 | 9 | Немає повного актуального Virtual Sensors screenshot evidence |
| 2 | 10 | 9 | Mock transport; немає physical BLE evidence |
| 3 | 10 | 10 | Durable outbox, WorkManager, security tests; terminal policy виправлена |
| 4 | 10 | 10 | Decision engine, manual/remote/ML actuator paths і cleanup |
| 5 | 10 | 10 | Room, reactive analytics, typed events, migrations 1→2→3 |
| 6 | 25 | 22 | Реальний TFLite і тести; немає реального датасету та повного screenshot evidence |
| 7 | 25 | 15 | Немає configurable persisted scenarios, profiler, full E2E, video, presentation |
| Разом | 100 | 85 | — |

## 12. Production readiness

**74/100.** Сильні сторони: data integrity, explicit migrations, lifecycle cleanup, owner isolation, durable retry, real interpreter tests. Блокери: synthetic-only ML, відсутність privacy controls, physical transport, soak/profiler evidence, release hardening і повного E2E.

## 13. Final weighted assessment

`0,70 × 85 + 0,30 × 74 = 81,7/100`.

## 14. Final verdict

- ЛР6: READY FOR DEFENSE WITH LIMITATIONS. Реальний inference доведено; screenshots слід дозібрати вручну.
- ЛР7: NOT FULLY READY. Основний pipeline існує, але mandatory configurable scenarios, profiler evidence і video відсутні.
- Система ЛР1–7: інтегрована частково; автоматизовані core contracts мають PASS.
- Production: NO-GO.
