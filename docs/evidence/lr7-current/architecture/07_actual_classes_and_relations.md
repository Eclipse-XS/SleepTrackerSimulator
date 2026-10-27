# SleepSense 2.0 / SleepTrackerSimulator: Actual Production Classes & Relations

Документ містить вичерпний опис фактичної кодової бази проєкту станом на фінальну ревізію Лабораторної роботи №7. Усі класи, шляхи та взаємозв'язки верифіковано в активному робочому середовищі.

---

## 1. Presentation & UI Layer (`com.example.sleeptrackersimulator.ui`)

Побудовано повністю на сучасному декларативному стеку **Jetpack Compose** та **Material Design 3**.

### Ключові класи:
- **`MainActivity.kt`**: Головна точка входу. Ініціалізує Koin/Dependency Injection, перевіряє системні дозволи (BLE, Notifications, Camera/Torch, Sensors, Body Sensors) та налаштовує `NavHost`.
- **`SleepSenseNavigation.kt`**: Визначає граф переходів та 5 основних вкладок споживчого інтерфейсу:
  - `Today` (моніторинг поточної сесії в реальному часі).
  - `History` (архів попередніх сесій та детальний аналіз трендів).
  - `Insights` (індекси якості сну, відновлення, гістограми фаз).
  - `Devices` (керування Bluetooth Smart Band та вибір транспорту).
  - `Settings` (налаштування хмари, порогів чутливості, локальної БД).
- **`DeveloperLabScreen.kt`**: Інженерна діагностична лабораторія на 7 табів:
  1. *Sensors*: Сирий потік акселерометра XYZ (20 Гц), спектральна візуалізація.
  2. *BLE*: Керування GATT Central, сканування периферії, моніторинг Heart Rate.
  3. *Cloud*: Стан Firebase RTDB, ручний тригер синхронізації Outbox.
  4. *Actuators*: Прямий тест вібромотора та ліхтарика (Torch).
  5. *Room*: Інспекція SQLite v7 таблиць, кількість записів, розмір БД.
  6. *ML*: Живий інференс TFLite моделі, ймовірності класів (REST, LIGHT, AWAKE).
  7. *Trace*: Системний журнал діагностичних подій та Performance метрик.
- **`LegacyLabScreen.kt`**: Спеціальний режим збереження зворотної сумісності для викладача (елементи керування ЛР1–ЛР6 на одному екрані).

### ViewModels:
- **`TodayViewModel.kt`**: Керує життєвим циклом активної сесії запису, зв'язується з `SessionCoordinator`.
- **`HistoryViewModel.kt`**: Реактивно спостерігає `Flow<List<SessionSummaryEntity>>` через Room.
- **`InsightsViewModel.kt`**: Розраховує гістограми розподілу фаз сну (Deep, Light, REM, Awake) та середній пульс.
- **`DevicesViewModel.kt`**: Відповідає за вибір між `MockDeviceTransport` та `AndroidGattDeviceTransport`.
- **`DeveloperLabViewModel.kt`**: Забезпечує прямий доступ до діагностичних датчиків і метрик.

---

## 2. Background Execution & State Coordination

### Ключові класи:
- **`RecordingService.kt`** (`service/`):
  - Foreground Service типу `FOREGROUND_SERVICE_TYPE_HEALTH`.
  - Забезпечує неперервну фонову роботу моніторингу під час блокування екрана та сну користувача.
  - Має постійне системне сповіщення (Sticky Notification) з кнопками Pause / Stop.
  - Автоматично відновлюється при перезапуску (`START_STICKY`).
- **`SessionCoordinator.kt`** / **`SessionCoordinatorImpl.kt`** (`core/`):
  - Центральний оркестратор життєвого циклу трекінгу сну.
  - Керує станами: `IDLE` -> `RECORDING` -> `PAUSED` -> `COMPLETED`.
  - Синхронізує прийом даних від `AndroidSensorManager` та `DeviceTransport`.
  - Передає буферизовані дані в `SignalProcessingPipeline`.

---

## 3. Sensors & Edge ML Inference

### Ключові класи:
- **`AndroidSensorManager.kt`** (`sensor/`):
  - Отримує апаратні покази `Sensor.TYPE_ACCELEROMETER` з частотою 20 Гц (період 50 мс).
  - Реалізує стабільну інтерполяцію часових міток для запобігання jank-ефекту.
- **`SignalProcessingPipeline.kt`** (`sensor/`):
  - Проводить нормалізацію сирих даних (віднімання гравітаційної складової 9.81 м/с²).
  - Фільтрація смуговим фільтром Баттерворта (0.5–5.0 Гц) для виділення рухової активності.
  - Формує ковзне вікно розміром 20 відліків × 3 осі (довжина 1.0 с).
- **`TfLiteActivityClassifier.kt`** (`ml/`):
  - Завантажує квантовану / dense модель `app/src/main/assets/activity_classifier.tflite` (11.1 КБ).
  - Виконує інференс на базі TensorFlow Lite Interpreter (час інференсу на пристрої: 8.2–36.5 мкс).
  - Повертає вектор Softmax: `[REST, LIGHT_SLEEP, AWAKE]`.
  - Повна відповідність holdout benchmark (точність 98.53%, macro F1 0.9853).

---

## 4. Smart Rules & Actuation Subsystem

### Ключові класи:
- **`SmartRuleEngine.kt`** (`smart/`):
  - Оцінює правила автоматизації сну (наприклад: тривале неспання вночі, підвищений пульс, тахікардія).
  - **Consecutive Matches Counter**: Запобігає хибним спрацьовуванням, вимагаючи $N$ послідовних збігів умови (наприклад, 3 вікна поспіль).
  - **Cooldown Manager**: Гарантує мінімальну паузу між спрацьовуваннями (наприклад, не частіше ніж раз на 15–30 с).
  - **Auto-Rearm Logic**: Вимагає повернення показників до норми перед можливістю повторної активації сценарію.
- **`SmartRuleEventCoordinator.kt`** (`smart/`):
  - Забезпечує **атомарну транзакцію в SQLite**: запис факту виконання `smart_rule_executions` разом зі створенням задачі в `cloud_outbox`.
  - Захищає від подвійного виконання та розсинхронізації в разі раптового збою живлення.
- **`AndroidSmartActuatorConfiguration.kt`** (`actuator/`):
  - Фізичний вібромотор через `Vibrator` / `VibratorManager` з підтримкою складних патернів вібрації.
  - Фізичний оптичний ліхтарик через `CameraManager.setTorchMode()`.
- **`DecisionEngine.kt`** (`actuator/`):
  - Маршрутизує команди між локальними актуаторами (вібрація, спалах) та зовнішнім BLE-браслетом.

---

## 5. Bluetooth Low Energy (BLE) Subsystem

### Ключові класи:
- **`DeviceTransport.kt`** (`ble/`):
  - Уніфікований інтерфейс для бездротового підключення:
    `connect()`, `disconnect()`, `observeHeartRate(): Flow<HeartRateData>`, `sendCommand(action)`.
- **`MockDeviceTransport.kt`** (`ble/`):
  - Детермінований програмний генератор фізіологічних показників для тестування без фізичного обладнання.
- **`AndroidGattDeviceTransport.kt`** (`ble/`):
  - Повноцінний BLE Central клієнт за стандартом Bluetooth SIG:
    - Heart Rate Service: `0x180D` (`0000180d-0000-1000-8000-00805f9b34fb`).
    - Heart Rate Measurement: `0x2A37` (підтримка 8-біт/16-біт BPM та RR-інтервалів).
    - Body Sensor Location: `0x2A38` (локація: Wrist).
    - Власний командний UUID для актуаторів: `7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e`.
- **`VirtualSleepBandAdvertiser.kt`** (модуль `virtualsleepband/`):
  - Окремий застосунок-компаньйон, що емулює апаратний фітнес-браслет на другому смартфоні через `BluetoothLeAdvertiser` та `BluetoothGattServer`.

---

## 6. Persistence & Room Database Schema v7

### Ключові класи:
- **`SleepTrackerDatabase.kt`** (`history/`):
  - База даних Room версії 7 (14 таблиць).
  - Підтримує валідовані міграції `MIGRATION_1_2` ... `MIGRATION_6_7`.
- **DAOs**:
  - `SessionDao` (`recording_sessions`).
  - `SensorSampleDao` (`sensor_samples`).
  - `HeartRateSampleDao` (`heart_rate_samples`).
  - `FeatureWindowDao` (`feature_windows`).
  - `PredictionDao` (`predictions`).
  - `SessionSummaryDao` (`session_summaries`).
  - `SmartRuleDao` & `SmartRuleEventCoordinatorDao` (`smart_rules`, `smart_rule_executions`).
  - `CloudOutboxDao` (`cloud_outbox`).
  - `DeviceDao` (`devices`).
  - `PersonalBaselineDao` (`personal_baselines`).

---

## 7. Cloud Synchronization & Resiliency

### Ключові класи:
- **`SyncOutboxWorker.kt`** (`cloud/`):
  - Фоновий воркер `androidx.work.CoroutineWorker`.
  - Спрацьовує при наявності мережевого з'єднання (`NetworkType.CONNECTED`).
  - Реалізує Exponential Backoff при помилках передачі даних.
- **`RoomCloudStateStore.kt`** (`cloud/`):
  - Забезпечує надійне локальне збереження статусу синхронізації (`syncedToCloud = 0 / 1`).
- **`database.rules.json`** (`firebase-tests/`):
  - Декларативні правила безпеки Firebase Realtime Database.
  - Валідують формати JSON, типи даних, діапазони фізіологічних значень (BPM 30..240) та запобігають крос-користувацькому доступу.

---

## 8. Матриця тестового покриття

| Підсистема | Юніт-тести (JVM, 197 тестів) | Інструментальні тести (Pixel 10 Pro, 22 тести) | Результат |
| :--- | :--- | :--- | :--- |
| **Edge ML & Preprocessing** | `ActivityClassifierTest`, `SignalProcessingPipelineTest` | `ActivityClassifierInstrumentedTest` (4 тести) | **PASS** (100%) |
| **Smart Rules & Logic** | `DecisionEngineTest`, `SmartRuleEngineTest` | `SmartRuleDaoTest`, `SmartRuleEventCoordinatorTest` (4 тести) | **PASS** (100%) |
| **Actuators (Haptic & Torch)** | `AndroidSmartActuatorConfigurationTest` | `CorrelatedPipelineE2ETest` (1 тест) | **PASS** (100%) |
| **BLE Subsystem** | `BleGattProtocolContractTest`, `MockBleConnectorTest` | `DataFlowIntegrationTest` (1 тест) | **PASS** (100%) |
| **Room v7 Migrations** | N/A (потребує реального SQLite) | `SleepTrackerMigrationTest`, `V5ToV6Test`, `V6ToV7Test` (6 тестів) | **PASS** (100%) |
| **Cloud Outbox & Recovery** | `SyncOutboxWorkerTest` | `RoomCloudStateStoreTest`, `CloudOfflineE2ETest` (3 тести) | **PASS** (100%) |
| **Firebase Security Rules** | 11 тестів через Firebase Emulator (`database.rules.test.mjs`) | N/A (перевірка середовища RTDB) | **PASS** (100%) |
