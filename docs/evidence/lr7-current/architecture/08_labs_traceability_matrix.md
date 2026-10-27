# SleepSense 2.0: Наскрізна матриця трасування лабораторних робіт (ЛР1–ЛР7)

Цей документ демонструє академічну спадкоємність та повну збереженість функціоналу всіх 7 лабораторних робіт у фінальній кодовій базі **SleepTrackerSimulator / SleepSense 2.0**. Жодна з попередніх робіт не була видалена чи зламана під час рефакторингу; навпаки, вони утворили єдину багатошарову production-архітектуру.

---

## Таблиця відповідності лабораторних робіт

| Лабораторна робота | Тематика завдання | Ключові класи в кодовій базі | Екран / Інтерфейс у застосунку | Фактичні тести для захисту |
| :--- | :--- | :--- | :--- | :--- |
| **ЛР-1** | Базова архітектура Android, Jetpack Compose, Material 3, Activity lifecycle | `MainActivity.kt`<br>`SleepSenseNavigation.kt`<br>`Theme.kt` | Вкладки `Today`, `Settings`, `LegacyLabScreen` | `MainActivityTest`<br>`NavigationSmokeTest` |
| **ЛР-2** | Апаратні сенсори, вимірювання прискорення XYZ, таймінги, фоновий сервіс | `AndroidSensorManager.kt`<br>`RecordingService.kt`<br>`SensorViewModel.kt` | `DeveloperLabScreen` (таб Sensors)<br>`LegacyLabScreen` | `RecordingServiceTest`<br>`SensorSamplingRateTest` |
| **ЛР-3** | Цифрова обробка сигналів, фільтр Баттерворта, спектральний аналіз, ковзні вікна | `SignalProcessingPipeline.kt`<br>`BaselineCalibrator.kt`<br>`FeatureWindowEntity.kt` | `DeveloperLabScreen` (спектр)<br>`InsightsScreen` (розподіл) | `SignalProcessingPipelineTest`<br>`FeatureExtractionUnitTest` |
| **ЛР-4** | Edge AI / Machine Learning, TFLite інференс, класифікація активності сну | `TfLiteActivityClassifier.kt`<br>`activity_classifier.tflite`<br>`PredictionEntity.kt` | `DeveloperLabScreen` (таб ML)<br>`TodayScreen` (поточна фаза) | `ActivityClassifierTest`<br>`ActivityClassifierInstrumentedTest`<br>`ml/evaluate_models.py` |
| **ЛР-5** | Бездротовий зв'язок Bluetooth Low Energy, GATT Central, Heart Rate Service (0x180D) | `DeviceTransport.kt`<br>`AndroidGattDeviceTransport.kt`<br>`VirtualSleepBandAdvertiser.kt` | Вкладка `Devices`<br>`DeveloperLabScreen` (таб BLE)<br>`VirtualSleepBand` APK | `BleGattProtocolContractTest`<br>`MockBleConnectorTest` |
| **ЛР-6** | Локальна персистентність, Room Database v1–v7, міграції схеми, агрегація сесій | `SleepTrackerDatabase.kt`<br>`SessionDao.kt`<br>`SessionSummaryDao.kt` | Вкладка `History`<br>`SessionDetailScreen`<br>`DeveloperLabScreen` (таб Room) | `SleepTrackerMigrationTest`<br>`SleepTrackerMigrationV6ToV7Test`<br>`SessionRecordDaoTest` |
| **ЛР-7** | Комплексна інтеграція: Smart Rules, мультимодальні актуатори, Firebase RTDB, Outbox | `SmartRuleEngine.kt`<br>`AndroidSmartActuatorConfiguration.kt`<br>`SyncOutboxWorker.kt`<br>`database.rules.json` | `DeveloperLabScreen` (Cloud, Actuators)<br>Сценарії автоматизації в `Settings` | `SmartRuleEngineTest`<br>`CorrelatedPipelineE2ETest`<br>`database.rules.test.mjs` (Firebase) |

---

## Педагогічні акценти для викладача

1. **Доступність двох інтерфейсів:**
   - **Споживчий (SleepSense 2.0):** Повноцінний сучасний інтерфейс для кінцевого користувача (Today, History, Insights, Devices, Settings).
   - **Академічно-інженерний (Developer Lab та Legacy Lab):** Дозволяє викладачеві під час захисту одним кліком відкрити будь-яку з 7 лабораторних робіт, подивитися сирі дані сенсорів, відправити команду на вібромотор чи спалах, протестувати BLE та проінспектувати таблиці Room.

2. **Відтворюваність результатів:**
   - Кожен крок підтверджено автоматизованими тестовими сюїтами (197 JVM-тестів та 22 On-Device тести на емуляторі Pixel 10 Pro).
   - Результати тестів збережено у форматі JUnit XML у папці `docs/evidence/lr7-current/test-results/`.
