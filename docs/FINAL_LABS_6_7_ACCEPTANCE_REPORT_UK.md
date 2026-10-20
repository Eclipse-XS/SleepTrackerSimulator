# Фінальний acceptance-звіт ЛР6–7

Дата локальної перевірки: 20 жовтня 2026 року.

## A. GitHub

- Репозиторій: `Eclipse-XS/SleepTrackerSimulator`.
- Гілка: `release/labs-1-7-verified`.
- Original verified SHA: `ab0bda4bf739422f52d1ed1719fbcefea1d257a1`.
- Final implementation SHA: буде зафіксовано після створення release commit; це не є доказом до push.
- PR: `https://github.com/Eclipse-XS/SleepTrackerSimulator/pull/1`.
- `main` не змінювався; merge і force-push не виконувалися.
- До початку робіт у worktree були сторонні untracked Lab 5/QA/tool artifacts. Вони не входять до release-набору.

## B. Initial defects

1. Execution і cloud outbox створювалися різними операціями, що залишало crash window.
2. `NOT_QUEUED` та проміжні записи не мали надійного автоматичного recovery.
3. Outbox міг пропустити незавершений rule payload або неконтрольовано розширити critical queue.
4. Room v5 не мав device-verified migration path до нової схеми.
5. Не було одного корельованого device E2E від TFLite до Firebase ACK.
6. Android instrumentation раніше було заблоковано відсутнім device.
7. Performance evidence та Office-артефакти не відповідали актуальному коду.

## C. Fixed defects

| Дефект | Причина | Виправлення | Перевірка |
|---|---|---|---|
| Crash window execution/outbox | Два незалежні записи | `RoomSmartRuleEventCoordinator` і одна Room transaction | atomic rollback/duplicate/full-outbox tests |
| Передчасна cloud delivery | Payload був видимий до outcome | `readyForDelivery` і FIFO head gate | DAO/store instrumentation |
| Невизначений outcome після crash | Не було state reconciliation | reconciler переводить проміжні стани в `OUTCOME_UNKNOWN` без actuator replay | recovery tests |
| Некоректна кореляція | Неповний execution snapshot | persisted trigger source, ML class/confidence, stable UUID | coordinator та E2E tests |
| Потенційно безмежний critical outbox | Винятки обходили hard cap | hard limit 10000, резерв 2 critical slots і 1 END_SESSION slot | saturation tests |
| Неповна migration verification | Лише compile-level довіра | Room v6, schema 6 export, MigrationTestHelper v1–v5→v6 | API 36 instrumentation |
| Firebase mutable event identity | Retry міг змінити ключові поля | immutable selected fields і schema validation | 8/8 emulator rules tests |

## D. Atomic event persistence

State machine: rule trigger → stable `executionId` → atomic insert execution + gated outbox → commit → actuator/BLE attempt → atomic outcome/payload finalization → `readyForDelivery=true` → Firebase write by `eventId` → local `SYNCED`.

Room transaction не охоплює Bluetooth, Android actuator або Firebase. Exactly-once фізичний ефект не заявляється. Якщо process death стався після можливого side effect, reconciler фіксує `OUTCOME_UNKNOWN` і не повторює фізичну дію. Firebase retry є логічно ідемпотентним через стабільний remote path. Gated FIFO head не дозволяє пізнішій операції обійти незавершений event.

Без активної cloud session execution не губиться: durable local record залишається для reconciliation. Вигадана session не створюється. UID не перепризначається під час recovery.

## E. Laboratory 6

- Asset: реальний `activity_classifier.tflite`, 11368 bytes.
- Tensor: float32 `[1,20,3] → [1,3]`; class order `REST`, `RESTLESS`, `ACTIVE`.
- Mean-centering міститься в model graph; Android подає raw XYZ.
- Window: 20 samples, monotonic timestamps, gap validation, bounded latest-window queue, один serial inference consumer.
- Python TFLite golden output для active vector: `[0.000016910271, 0.000010084729, 0.999972939491]`.
- Android golden parity: PASS, absolute tolerance `< 1e-5`.
- Synthetic test accuracy 97,22% не є клінічною або польовою точністю.

Оцінка готовності: **93/100, READY WITH LIMITATIONS**. Відсутні physical-device sampling trace, реальний датасет і release benchmark.

## F. Laboratory 7

- Smart Rules: typed source/operator/action, consecutive matches, rising edge, cooldown, durable execution snapshot.
- Room: v6, explicit 5→6 migration, exported schema, bounded outbox.
- Firebase: owner-scoped session event, stable event UUID, validated immutable event identity.
- BLE: `VirtualSleepBand` змінює alert state; ACK містить event ID і revision; duplicate retry не збільшує revision.
- Dashboard: фактичні sensor/ML/BLE/Room state sources.
- Correlated E2E: actual Android TFLite → rule → atomic Room → Mock BLE ACK → outbox → Auth/RTDB emulators → local `SYNCED`; 1/1 PASS.
- Offline/process-death semantics перевірені на рівні durable/recovery компонентних тестів. Окремий повністю ручний network-toggle/process-kill video сценарій не виконано.

Оцінка готовності: **90/100, READY WITH LIMITATIONS**. Performance FAIL, фізичний BLE не перевірений, повне desktop video відсутнє.

## G. Android instrumentation

- Device: `Pixel_10_Pro` AVD, Android API 36.
- `connectedDebugAndroidTest`: **18/18 PASS**, 0 failures, 0 errors.
- Correlated Firebase E2E з `firebaseE2E=true`: **1/1 PASS**.
- MigrationTestHelper: published schemas v1, v2, v3, v4, v5 → v6 і reopen: PASS.
- Actual TFLite inference та golden parity на Android: PASS.
- Smart Rule transaction/recovery/outbox instrumentation: PASS.

Звичайний suite використовує guard для E2E, якщо Firebase emulators не запущені. Тому 18/18 не підмінює окремий E2E 1/1.

## H. Performance

Попередній baseline: 1028 frames, 946 modern janky frames, 92,02%, p95 близько 101 ms.

Контрольний 15-second Dashboard debug AVD segment: 333 frames, 303 modern janky frames (90,99%), legacy jank 17,72%, p50 18 ms, p90/p95 34 ms, p99 57 ms, TOTAL PSS 133251 KB, TOTAL RSS 269060 KB.

Вердикт: **FAIL**. p95 зменшився, але modern jank залишається неприйнятним. Шість ізольованих режимів, Perfetto root-cause і physical release benchmark не завершені. Дані: `docs/evidence/lab7/final-acceptance/PERFORMANCE_2026-10-20.md`.

## I. Security

- Firebase Emulator Suite: 8/8 PASS.
- Перевірено owner read/write, default/cross-user denial, invalid action/confidence/timestamp/event ID/extra fields, idempotent retry, changed overwrite та cross-user delete denial.
- Rule event identity fields після створення незмінні.
- Live Firebase Rules не розгорталися.
- Credentials не додавалися. Репозиторій має пройти фінальний secrets scan перед commit.
- Owner може видалити власну session як parent subtree; заборона child delete не є абсолютною через каскадний parent write у RTDB.

## J. Academic deliverables

- `deliverables/ЛР-6_ПМСС_Гончаров_Вадим_ОІ-43.docx` — оновлено.
- `deliverables/ЛР-7_ПМСС_Гончаров_Вадим_ОІ-43.docx` і PDF — оновлено.
- `deliverables/ЛР-7_Презентація_SleepTrackerSimulator.pptx` — 13 слайдів, валідовано і візуально перевірено.
- Вісім Mermaid diagrams оновлено для Room v6, atomic boundary, gate, recovery, BLE ACK та error path.
- Корельований record: `docs/evidence/lab7/final-acceptance/CORRELATED_E2E_2026-10-20.txt`.
- Full desktop video: **PARTIAL**. Наявний попередній app video не доводить повний новий сценарій з Extended Controls і Firebase desktop view.

## K. Test results

| Перевірка | Результат |
|---|---|
| `testDebugUnitTest` | 159/159 PASS |
| `assembleDebug` | PASS |
| `assembleRelease` | PASS |
| `lintDebug` | PASS |
| `compileDebugAndroidTestSources` | PASS |
| `connectedDebugAndroidTest` | 18/18 PASS |
| Dedicated correlated E2E | 1/1 PASS |
| Firebase rules | 8/8 PASS |
| Full Gradle regression | PASS, 140 actionable tasks, 2m03s |
| Performance acceptance | FAIL |
| Full desktop video | PARTIAL |

## L. Remaining issues

Підтверджені дефекти:

1. Debug AVD modern frame jank — 90,99%; production performance acceptance провалено.
2. Повний desktop video для нового E2E не створено.

Неперевірені ризики:

1. Реальний BLE hardware transport і permissions на фізичних моделях телефонів.
2. Release performance, thermal behavior та long soak на фізичному пристрої.
3. Якість ML на реальних людях і різних положеннях пристрою.
4. Manual network toggle + OS process kill у повному UI сценарії; component recovery semantics покриті, але це не тотожний доказ.
5. Production Firebase deployment та Play Console hardening.

## M. Final verdict

- **Lab 6: READY WITH LIMITATIONS.**
- **Lab 7: READY WITH LIMITATIONS.**
- **Production: NO-GO.** Причини: performance FAIL, відсутня реальна ML validation, physical BLE і physical-device release benchmark.
- **GitHub: LOCAL VERIFIED; publication/CI verdict встановлюється тільки після commit, push і перевірки remote SHA та Actions.**
