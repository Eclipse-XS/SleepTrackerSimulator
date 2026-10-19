# Фінальний технічний аудит лабораторної роботи №7

## 1. Baseline репозиторію

Гілка `fix/pre-lab6-reliability`, початковий HEAD `f1fb5805`. Робоче дерево містило незакомічені зміни попередніх лабораторних проходів; їх збережено. Commit і push не виконувались. Вільний простір C: перед основною роботою становив приблизно 46.6 GB.

## 2. Початкові розриви

Не було persistent configurable Smart Scenarios, останніх п'яти Room records на Dashboard, BLE alert command, Lab 7 video/profiling/traceability/report/presentation. Тверда ML-triggered дія Lab 6 не замінювала user-configurable rule.

## 3. Реалізація

Додано typed rule model, Room entities/DAO/repository, migration 3→4, pure `SmartRuleEngine`, orchestration ViewModel, Compose CRUD screen, global visible AlertDialog, BLE `ALERT`, Dashboard recent records та автоматизовані тести. Стабільні Labs 1–6 компоненти не переписувались.

## 4. Smart Scenarios

Sources: X/Y/Z у m/s², movement intensity та ML class. Numeric operators: `>`, `>=`, `<`, `<=`; ML дозволяє equals. Actions: vibration pulse, Mock BLE alert, visible alert і combined critical alert. Rule state використовує consecutive count, rising edge, rearm та monotonic cooldown. Після process restart transient runtime state обнуляється, persistent configuration лишається.

## 5. Повна інтеграція

SensorManager подає `SensorSample`. `SensorViewModel` формує UI state; history recorder пише Room; `MlViewModel` накопичує 20×3 window і викликає TFLite; Smart Rule coordinator оцінює sensor/ML state; side effects виконуються окремо й записують фактичний результат. Cloud monitoring використовує іншу асинхронну гілку через durable outbox. Це не одна атомарна транзакція.

## 6. Mock BLE delivery

`DeviceCommand.ALERT` доходить до `MockBleConnector` лише у connected state. Connector повертає і логує `ALERT_ACKNOWLEDGED`. Unit test перевіряє command/result; disconnected error не маскується як delivery.

## 7. Dashboard

Показує sensor diagnostics, movement, BLE і HR, ML state/confidence, Firebase/outbox status, actuator controls і п'ять найновіших записів із Room Flow.

## 8. Offline synchronization

Cloud operation спочатку потрапляє до Room outbox. Network failure зберігає pending state, WorkManager повторює delivery. Firebase Emulator Suite перевіряє auth/rules. Реальну production Firebase не змінювали.

## 9. False positives

За замовчуванням правило потребує три послідовні збіги. Trigger виконується один раз, доки condition не стане false. Наступний trigger дозволено після rearm і cooldown. Boundary і sustained-condition перевірено JVM tests.

## 10. Профілювання

60-секундний debug AVD run: TOTAL PSS 115111 KB після старту, 130093 KB на 30 с, 129377 KB на 60 с; swap 0. Після десяти force-stop/start cycles PSS 92940 KB. `gfxinfo`: 1028 frames, 92.02% janky за сучасною метрикою, p50 20 ms, p90 65 ms, p95 101 ms, p99 250 ms. Це production blocker для UX до перевірки release build на фізичному пристрої.

## 11. Автоматизовані тести

Фінальний запуск: `testDebugUnitTest` — 155 tests, 0 failures; `connectedDebugAndroidTest` — 11 tests, 0 failures; Firebase Emulator Suite — 4 tests, 0 failures. `assembleDebug`, `assembleRelease`, `lintDebug` і `compileDebugAndroidTestSources` завершилися `BUILD SUCCESSFUL`.

## 12. Manual/E2E

На AVD створено правило `X > 10 → VIBRATION_PULSE`; screenshot підтверджує збережену entity, а реальний screenrecord фіксує UI flow. Повний desktop цикл з Extended Controls і Firebase Console не записаний, тому TC-L7-28 має статус PARTIAL.

## 13. Артефакти

- Матриця: `docs/LAB7_REQUIREMENTS_MATRIX_UK.md`.
- Діаграми: `docs/diagrams/lab7/`.
- Профілювання: `docs/evidence/lab7/profiling/`.
- Screenshots/video: `docs/evidence/lab7/`.
- Сценарій demo: `docs/LAB7_DEMO_SCRIPT_UK.md`.
- Defense guide: `docs/DEFENSE_GUIDE_LAB7_UK.md`.
- Report і presentation: `deliverables/`.

## 14. Залишкові обмеження

Окремий smart-rule execution зберігається локально, але не має власного типу Firebase outbox payload. Наявне відео не показує весь цикл. GUI Android Studio Profiler і desktop Extended Controls не потрапили у recording. TFLite synthetic accuracy не доводить медичну точність. Mock BLE не є фізичним BLE GATT. Debug AVD має значний frame jank.

## 15. Оцінка

| Категорія | Бал | Причина втрати |
|---|---:|---|
| Інтеграція Labs 1–6 | 18/20 | smart execution не має окремого cloud event payload |
| Persistent Smart Scenarios | 19/20 | runtime rearm/cooldown state не переживає process death |
| Dashboard | 10/10 | реальні state sources і Room records |
| Actuator та Mock BLE | 9/10 | emulator не підтверджує фізичну вібрацію |
| Offline recovery | 9/10 | немає нового manual video recovery |
| Integration/E2E | 7/10 | full TC-L7-28 не записано |
| Profiling | 3/5 | реальні метрики, але без GUI trace/heap dump; jank високий |
| Документація | 5/5 | traceability, diagrams, report, guide |
| Відео | 2/5 | genuine, але часткове |
| Report/presentation | 5/5 | створено та перевірено |
| **Разом** | **87/100** | engineering rubric, не офіційна оцінка викладача |

## 16. Вердикт

**READY FOR LAB 7 DEFENSE WITH LIMITATIONS.** Основні навчальні функції реалізовані й автоматизовано перевірені; викладачеві слід прямо назвати partial video та відсутність окремої cloud-події правила.

**Production NO-GO.** Потрібні фізичний BLE, release profiling, зниження jank, медична валідація, повний security/privacy review та end-to-end observability.
