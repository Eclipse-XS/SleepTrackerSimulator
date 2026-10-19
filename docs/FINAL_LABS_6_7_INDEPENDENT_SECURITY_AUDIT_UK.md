# Незалежний security та reliability аудит Labs 6–7

## Підтверджені знахідки

| Severity | Знахідка | Виправлення | Статус |
|---|---|---|---|
| P1 | ML читав conflated UI StateFlow | `SensorViewModel.samples` є ordered non-conflated SharedFlow від одного listener; ML читає його напряму | FIXED |
| P1 | Rule execution не має власного Firebase event payload | Не виправлено у цьому проході: потрібне versioned розширення outbox/repository/rules | OPEN |
| P2 | Window допускав unrelated timestamps | Reset при non-monotonic timestamp або gap >100 ms | FIXED |
| P2 | Некоректні float values могли пройти далі | Sensor bounds/finite validation і output probability validation | FIXED |
| P2 | 92.02% jank трактувався надто широко | Класифіковано як debug AVD observation, не release benchmark | DOCUMENTED |

## Lab 6

Модель і labels узгоджені: REST, RESTLESS, ACTIVE; float32 `[1,20,3] → [1,3]`. Mean subtraction входить до model graph, тому Kotlin передає raw XYZ у тому самому порядку. Sampling request — 50 000 µs. Temporal buffer відкидає gaps понад 100 ms і non-monotonic timestamps. Synthetic held-out accuracy 97.22% не є real-world або clinical validation.

Оцінка Lab 6: **90/100**. Втрата балів: synthetic-only provenance, відсутність golden Python/Kotlin probability comparison і фізичного sampling trace.

## Lab 7

Persistent Smart Scenarios, Room v4, migration tests, actuator result і Mock BLE acknowledgement підтверджені. Rule engine має consecutive matching, rising edge, rearm і cooldown. Відкритий дефект: execution event не став окремою durable cloud operation, тому end-to-end Rule→Firebase не завершений.

Оцінка Lab 7: **82/100**. Основні втрати: open cloud-event P1, неповне E2E video, debug-only performance evidence.

## Security

Firebase має default-deny owner-scoped paths і validation відомих полів. Anonymous/cross-user access тести проходять. Backup/device transfer для monitoring data вимкнено. Service-account files, keystores, local.properties та google-services.json виключені `.gitignore`. Client Firebase identifiers не класифікуються як secret, але локальний `google-services.json` не публікується.

## Production verdict

**NO-GO.** Потрібні durable Smart Rule cloud event, UID ownership migration policy, physical-device release profiling, real BLE transport, data deletion UX, real-world ML validation та complete correlated E2E.
