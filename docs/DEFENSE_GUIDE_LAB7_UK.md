# Захист лабораторної роботи №7

1. **Мета проєкту.** Інтегрувати датчик руху, edge ML, локальне й хмарне зберігання, BLE-імітацію та actuator actions у Sleep Tracker Simulator.
2. **Завдання Lab 7.** Додати конфігуровані Smart Scenarios, звести компоненти Labs 1–6 в один потік і перевірити відмовостійкість.
3. **Інтеграція Labs 1–6.** Один SensorManager source живить UI, Room, TFLite і правила. Cloud та actuator є асинхронними гілками.
4. **Джерело даних.** `AndroidAccelerometerDataSource` реєструє listener лише у foreground і видає X/Y/Z у m/s².
5. **Кільцевий буфер.** Модель потребує рівно 20 послідовних тривісних samples, тому `MlViewModel` накопичує ковзне вікно.
6. **TFLite.** `ActivityClassifier` подає tensor `[1,20,3]` і читає `[1,3]`.
7. **Класи.** REST, RESTLESS, ACTIVE описують навчальні патерни руху, не медичні стадії сну.
8. **ML до action.** Правило з source `ML_CLASS` порівнює target class; coordinator виконує обрану дію і записує фактичний результат.
9. **Threshold і class.** Numeric sources використовують `>`, `>=`, `<`, `<=`; ML source дозволяє лише equality.
10. **Rule Engine.** Це чистий Kotlin-компонент. Він рахує consecutive matches, спрацьовує на rising edge, очікує reset і cooldown.
11. **Зберігання правил.** Room v4, таблиці `smart_rules` і `smart_rule_executions`.
12. **Room.** Локальна SQLite abstraction з DAO, Flow і перевіреними migrations 1→2→3→4.
13. **Firebase.** Realtime Database приймає monitoring payload після anonymous auth.
14. **WorkManager.** Відновлює доставку pending outbox після повернення мережі або процесу.
15. **Outbox.** Спочатку фіксує операцію локально. Відсутність acknowledgement не видається за успіх.
16. **Зникнення мережі.** Sensor, ML і Room продовжують роботу; cloud operation лишається pending.
17. **Повернення мережі.** Worker повторює idempotent operation і змінює стан після acknowledgement.
18. **Mock BLE.** Керована state machine VirtualSleepBand, а не твердження про фізичний GATT-пристрій.
19. **BLE alert.** `DeviceCommand.ALERT` обробляється connector і повертає `ALERT_ACKNOWLEDGED`; disconnected state повертає error.
20. **Actuators.** Локальні controls діють через `AndroidSmartActuator`; remote controls проходять Firebase repository. Flashlight залежить від permission і hardware.
21. **False positives.** Три consecutive matches, rising edge і cooldown. Утримання condition true не створює повторні pulses.
22. **Повний outbox.** Capacity guard не видаляє pending payload мовчки; END_SESSION має окремий безпечний шлях.
23. **Немає sensor.** UI отримує unavailable/error state, listener не симулює успішні дані.
24. **Profiler.** 60 с adb profiling: PSS 115111→130093→129377 KB; після 10 restart 92940 KB. `gfxinfo` показав високий jank у debug AVD.
25. **Тести.** JVM rule tests, BLE tests, Room DAO/migration instrumentation, TFLite instrumentation, connected suite і Firebase emulator security tests.
26. **Обмеження ML.** Synthetic dataset дає 97.22% на незалежному synthetic set, але не валідований на людях.
27. **Production improvements.** Реальний BLE GATT, клінічні дані, encrypted local storage, App Check, фізичне profiling і повний observability pipeline.

## Чекліст live demo

- Dashboard і поточні реальні стани.
- BLE scan/connect/HR.
- Create rule, restart, persisted rule.
- Extended Controls: X crosses threshold three times.
- Execution result та anti-spam.
- Mock BLE acknowledgement.
- AI class та останні п'ять Room records.
- Offline pending, online recovery, session end.
