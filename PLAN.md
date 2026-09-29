# SleepTrackerSimulator: технічний аудит і наскрізний план ЛР1–ЛР7

Дата аудиту: 2026-09-28

## 0. Межі цього етапу

На цьому етапі реалізація лабораторних не починалась. Не створено `MainActivity`, Kotlin-класи, Compose UI, моделі БД або ML; не змінено Gradle та Manifest. Єдина додана сутність — цей план.

Принцип виконання: одна лабораторна розширює один застосунок. Перехід до наступної лабораторної дозволений лише після збірки, автоматичних перевірок, ручної перевірки на емуляторі та збереження матеріалів для звіту поточної лабораторної.

## 1. Поточний стан проєкту

### Що є

- Один Gradle-модуль `:app`; `settings.gradle.kts` коректно підключає його.
- Android Gradle Plugin 9.4.1, Gradle Wrapper 9.6.0, compile/target SDK 37, min SDK 31.
- Встановлена платформа Android SDK `android-37.0` та Build Tools `36.0.0`.
- Namespace/applicationId: `com.example.sleeptrackersimulator`.
- Ресурси теми, launcher icon, backup/data-extraction rules.
- Порожній `AndroidManifest.xml` із `<application />`, без Activity та permissions.
- Шаблонні JUnit та instrumented tests.
- Version catalog `gradle/libs.versions.toml`.
- `gradlew tasks --all` успішно конфігурує проєкт.

### Чого немає

- Kotlin Android/Compose plugin і Compose-конфігурації.
- `app/src/main/java` або `app/src/main/kotlin` з кодом застосунку.
- Launcher Activity, navigation, UI та application architecture.
- Lifecycle/ViewModel, Coroutines, Room, Firebase, chart і TensorFlow Lite dependencies.
- Сенсорів, BLE mock, cloud sync, actuator, local storage, ML та інтеграційних тестів.
- Конфігурації Firebase та `.tflite` model.

### Оцінка коректності

Це структурно коректний Android-проєкт типу **No Activity**, але ще не працездатний користувацький застосунок. `minSdk = 31` точно відповідає вимозі API 31+.

Поточна build-верифікація має блокер середовища: `gradle/gradle-daemon-jvm.properties` вимагає Java toolchain 25, у shell доступний Java 11. Gradle спробував завантажити JDK 25 через Foojay, але запит завершився мережевою помилкою. Отже, `tasks` працює, а `assembleDebug/test/lint` ще не підтверджені. У Phase 0 слід використати встановлений JDK 25 з Android Studio або встановити/дозволити завантаження JDK 25. Не слід довільно знижувати toolchain без перевірки вимог AGP 9.4.1.

Окреме застереження: Git root виявився вище каталогу проєкту (`C:\Users\Eclipse`), тому звичайний `git status` охоплює весь профіль користувача. До комітів треба або ініціалізувати окремий репозиторій у `SleepTrackerSimulator`, або явно обмежувати шляхи. Інакше є ризик випадково включити сторонні файли.

## 2. Архітектурні рішення

### 2.1 Форма проєкту

До ЛР7 достатньо одного `app`-модуля. Фізичне розбиття на багато Gradle-модулів для навчального масштабу додасть конфігураційну складність без користі. Межі компонентів забезпечуються package-структурою та інтерфейсами. Якщо застосунок реально зросте після курсу, `data`, `domain` і `feature` можна винести в модулі без зміни контрактів.

### 2.2 Package-структура

```text
com.example.sleeptrackersimulator/
  app/
    MainActivity.kt
    SleepTrackerApplication.kt          # лише коли потрібна app-scope ініціалізація
    AppContainer.kt                     # manual DI
  core/
    model/                              # SensorSample, ActivityState, Prediction
    time/                               # Clock abstraction для тестів
    util/
  sensor/
    AccelerometerDataSource.kt
    AndroidAccelerometerDataSource.kt
    MotionPreprocessor.kt               # gravity removal/filter/features
  ble/
    IBleConnector.kt
    MockBleConnector.kt
    MockBleScanner.kt
    BleModels.kt
  cloud/
    CloudDataSource.kt
    FirebaseCloudDataSource.kt
    CloudCommandSource.kt
    SyncCoordinator.kt
  actuator/
    DecisionEngine.kt
    ActuatorController.kt
    AndroidActuatorController.kt
  data/
    SleepRepository.kt                  # єдиний orchestration facade для ViewModel
    local/
      SensorRecordEntity.kt
      SensorRecordDao.kt
      SleepDatabase.kt
    mapper/
  ml/
    CircularSensorBuffer.kt
    ActivityClassifier.kt
    TfliteActivityClassifier.kt
    ModelMetadata.kt
  ui/
    dashboard/
    analytics/
    components/
    theme/
    navigation/
  worker/                               # лише якщо WorkManager буде виправданий ЛР7
```

Правила залежностей:

- UI читає immutable `UiState` і надсилає user intents у ViewModel.
- Android API обгортаються data-source/controller класами; `DecisionEngine`, preprocessing і domain models не залежать від Android.
- `SleepRepository` координує потоки, але не містить UI-стану.
- Room у фінальній версії є локальним source of truth. Cloud синхронізує записи зі статусом `PENDING`, а не читає сирі sensor callbacks напряму.
- Firebase, TFLite, SensorManager та VibratorManager стоять за інтерфейсами. Це запобігає переписуванню UI й дає fake implementations у тестах.
- Manual DI через `AppContainer` достатній. Hilt не додається без реальної потреби: для одного модуля він збільшить plugin/KSP surface і ускладнить звіт.

### 2.3 Потік даних у фінальній системі

```text
SensorManager
  -> AndroidAccelerometerDataSource
  -> MotionPreprocessor
  -> StateFlow<SensorSample + motion score>
  -> CircularSensorBuffer -> TfliteActivityClassifier
  -> DecisionEngine
  -> SleepRepository -> Room (source of truth, sync status)
                    -> SyncCoordinator -> Firebase Realtime Database
  -> ActuatorController
  -> MockBleConnector
  -> DashboardViewModel -> Compose Dashboard / Analytics
```

### 2.4 Предметна логіка без медичних тверджень

Смартфон на ліжку/поруч із ним фіксує лише рухову активність пристрою. Застосунок не визначає медичні фази сну й не діагностує стан людини.

- ЛР1: `Still` / `Movement` за motion score після видалення gravity та згладжування.
- ЛР6: `Still`, `Restless`, `Awake-like movement` за часовим вікном. Це класи патернів руху, не фази сну.
- ЛР4 actuator: віртуальна bedside lamp відображає стан; при тривалому `Restless`/сильному русі виконується короткий тактильний test alert і надсилається команда mock hub. Постійно вібрувати під час сну нелогічно, тому vibration має cooldown і може бути вимкнена. Flashlight — optional capability demo з перевіркою hardware; основним actuator в емуляторі є віртуальна лампа в UI.

## 3. Dependency strategy

Версії треба фіксувати у version catalog після перевірки compatibility matrix з AGP 9.4.1, Gradle 9.6 та Kotlin/Compose plugin. Заборонені `+` versions.

| Компонент | Рішення | Обґрунтування |
|---|---|---|
| Kotlin + Compose | Kotlin Android plugin, Compose plugin/BOM, Material 3, activity-compose | Вимагається завданням; BOM узгоджує Compose libraries. |
| Lifecycle | lifecycle-runtime-compose, lifecycle-viewmodel-compose | lifecycle-aware collection та ViewModel без ручного спостереження. |
| Coroutines | kotlinx-coroutines-android, coroutines-test | `Flow`, debounce/sample, IO/Default dispatchers і deterministic tests. |
| Navigation | navigation-compose | Достатньо для Dashboard/Analytics; додається лише коли з'явиться другий екран. |
| Room | room-runtime, room-ktx, room-compiler через KSP | Flow DAO і compile-time SQL validation. KSP переважний над kapt, але сумісність версій перевіряється до додавання. |
| Cloud | Firebase BoM + Realtime Database + google-services plugin | ЛР4 потребує push cloud-to-device listener; REST polling гірше відповідає вимогам і додає backend lifecycle. |
| Network state | Android `ConnectivityManager`/`NetworkCallback` | Окрема бібліотека не потрібна. Наявність мережі не дорівнює успішній синхронізації, тому status формується також із результату Firebase write. |
| Chart | Спочатку власний lightweight Compose `Canvas` LineChart | Для останніх 50 точок зовнішня chart library не обов'язкова; менше compatibility risk. Vico оцінити лише якщо потрібні axes/zoom/accessibility, які невигідно реалізовувати вручну. |
| TFLite | TensorFlow Lite Interpreter або LiteRT equivalent, зафіксована сумісна версія | Потрібен контроль input/output tensors. Task Sensors не додавати, доки модель не вимагає його metadata/API. |
| JSON | Firebase mapper/data classes | Gson/Retrofit не потрібні при виборі Firebase. Для демонстрації JSON можна показати структуру payload у Realtime Database і unit serialization test за потреби. |
| DI | Manual `AppContainer` | Прозоро для навчального проєкту, легко тестувати, без Hilt/KSP overhead. |
| Background sync | Спочатку coroutine + Firebase offline persistence; WorkManager лише для гарантованої deferred sync у ЛР7 | Не додавати WorkManager раніше, ніж визначено реальну вимогу process death/background constraints. |

## 4. Roadmap лабораторних

### ЛР1 — Sensors + Compose UI

**A. Успадковано:** каркас `:app`, minSdk 31, тема/ресурси, version catalog.

**B. Додати:** Kotlin/Compose setup; launcher Activity; `SensorSample`; lifecycle-aware accelerometer source; low-pass/high-pass або magnitude-based preprocessing; threshold з hysteresis для `Still/Movement`; ViewModel/StateFlow; X/Y/Z, magnitude/motion score та Canvas indicator; стан `Sensor unavailable`.

**C. Файли:** Gradle/catalog, Manifest, `MainActivity`, `sensor/*`, `core/model/*`, `ui/dashboard/*`, `ui/theme/*`, unit tests preprocessing/threshold, instrumented lifecycle/UI smoke test.

**D. Dependencies:** Kotlin/Compose, activity-compose, Material 3, lifecycle runtime/viewmodel Compose, Coroutines, Compose UI tests.

**E. Permissions/API:** `SensorManager`, `Sensor.TYPE_ACCELEROMETER`, `SensorEventListener`; runtime permission не потрібен. Реєстрація на `ON_RESUME`, `unregisterListener` на `ON_PAUSE`/dispose.

**F. Codex:** уся конфігурація, код, unit/UI tests, build/lint, документація threshold.

**G. User:** запустити AVD API 31+, змінити pose/movement у Extended Controls → Virtual Sensors, зафіксувати фактичні значення й screenshots.

**H. Перевірка:** build/test/lint green; на pause callbacks припиняються; X/Y/Z змінюються; indicator реагує; стабільний пристрій не перемикає стан через шум; відсутній sensor показує fallback.

**I. Артефакти:** структура проєкту; код listener/lifecycle/preprocessing; 2–3 screenshots різних положень і станів; опис AVD; таблиця threshold; Logcat без callbacks після pause.

### ЛР2 — Mock BLE / IoT

**A. Успадковано:** sensor stream, dashboard, ViewModel/StateFlow, Coroutines.

**B. Додати:** фіксований mock GATT profile (service/status/command UUID); `IBleConnector`; `MockBleScanner` із приблизно 2 s delay; state machine `Idle -> Scanning -> DeviceFound -> Connecting -> Connected -> Transferring/Error`; bounded on-screen log; передача компактного sensor payload/команди.

**C. Файли:** `ble/IBleConnector.kt`, `MockBleConnector.kt`, `MockBleScanner.kt`, `BleModels.kt`, dashboard BLE section, ViewModel integration, fake-clock/coroutine tests.

**D. Dependencies:** нова production dependency не потрібна; наявних Coroutines/StateFlow достатньо.

**E. Permissions/API:** оскільки реалізація повністю mock і не викликає Android Bluetooth APIs, технічно `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, location та runtime requests не потрібні. Додавати небезпечні permissions лише «для вигляду» неправильно. Якщо викладач буквально вимагає Manifest entries, це треба узгодити й чітко позначити у звіті як декларації майбутньої real implementation; код не повинен просити їх для mock flow.

**F. Codex:** інтерфейс, mock, state machine, UI/logging, UUID table, tests.

**G. User:** вручну пройти scan/connect/send flow на емуляторі й зробити screenshots/Logcat.

**H. Перевірка:** scan не знаходить device раніше delay; повторний scan cancel-safe; send до connection повертає контрольовану помилку; connected send дає Transferring і log; disconnect очищає state.

**I. Артефакти:** UUID table; state diagram; знайдений device; Connected/Data Transfer UI; Logcat із payload; listings interface/mock.

### ЛР3 — Cloud Sync

**A. Успадковано:** sensor payload, Flow/coroutines, dashboard state, mock BLE незалежний від cloud.

**B. Додати:** Firebase Realtime Database; `SensorPayload`; `CloudDataSource`; device-scoped path; sampling приблизно раз на 5 s або significant delta; explicit states `Idle/Syncing/Synced/Pending/Error`; Connectivity callback; Firebase disk persistence; retry semantics та timestamp/idempotency key.

Firebase обрано замість Retrofit/MockAPI, бо ЛР4 вимагає реактивні cloud-to-device commands. Один backend для ЛР3–4 зменшує переписування. На ЛР3 Firebase offline queue може демонструвати Pending; у ЛР5 Room стане повним source of truth із persisted sync status.

**C. Файли:** google-services integration, Manifest, `cloud/*`, payload/domain mapper, dashboard cloud card, repository/ViewModel changes, rules template/documentation, unit tests із fake cloud source.

**D. Dependencies:** Google services plugin; Firebase BoM; Realtime Database; Coroutines integration за потреби. Retrofit/Gson не додавати.

**E. Permissions/API:** `INTERNET`, `ACCESS_NETWORK_STATE`; Firebase SDK; мережевих runtime permissions немає.

**F. Codex:** integration code, interfaces/fakes, sync state/retry logic, database path/schema, tests, build verification. Codex не може створити Firebase account/project замість користувача.

**G. User:** створити Firebase project/app з точним applicationId, увімкнути Realtime Database, завантажити `google-services.json`, визначити безпечні навчальні rules, перевірити Console та Network Inspector.

**H. Перевірка:** online write з'являється в правильному device path; frequency bounded; airplane/offline дає Pending без crash; після network restore запис підтверджується; malformed/permission-denied має Error.

**I. Артефакти:** sequence diagram; Firebase data screenshot; cloud status online/offline; Network Inspector; repository listing; JSON payload example; rules screenshot без секретів.

### ЛР4 — Actuators / Decision Engine

**A. Успадковано:** filtered motion score/state, Firebase realtime connection, dashboard, mock BLE.

**B. Додати:** pure `DecisionEngine`; configurable threshold, duration, hysteresis/cooldown; `ActuatorController`; virtual bedside lamp; short vibration alert; optional torch guarded by feature/camera checks; manual override; Firebase command listener with command id/timestamp and acknowledgement; policy precedence (manual/cloud/automatic).

**C. Файли:** `actuator/*`, cloud command models/source, controls UI, repository/ViewModel integration, DecisionEngine unit tests, controller instrumentation tests where feasible.

**D. Dependencies:** нова library не потрібна; Android framework APIs + Firebase already present.

**E. Permissions/API:** `VIBRATE`; `VibratorManager` API 31; `CameraManager`, `FEATURE_CAMERA_FLASH`; camera permission зазвичай не потрібен лише для torch API, але camera availability/access exceptions обов'язково обробляються. Реальний torch не є acceptance criterion для AVD без flash.

**F. Codex:** decision logic, Android wrappers/fakes, manual/cloud control, acknowledgements, tests.

**G. User:** вручну змінити command у Firebase Console; перевірити vibration/virtual lamp; перевірити, чи AVD оголошує flash; зняти screenshots/video.

**H. Перевірка:** поріг не флапає через hysteresis; cooldown працює; manual off має визначений пріоритет; cloud command виконується один раз; unsupported vibrator/flash дає UI status, не crash; listener видаляється lifecycle-aware.

**I. Артефакти:** DecisionEngine flowchart/listing; manual controls; Firebase command + acknowledgement; actuator active/inactive; capability fallback; event-driven vs polling analysis.

### ЛР5 — Room + Analytics

**A. Успадковано:** domain models, events, action status, repositories/interfaces, coroutines.

**B. Додати:** Room Entity/DAO/Database; repository as local source of truth; fields `id`, `timestamp`, xyz, motionScore, ruleState, aiState nullable, actionTriggered, syncStatus/retryCount; sampled/significant writes rather than every callback; Flow last N; retention policy; Analytics screen; line chart last 50; migration tests.

**C. Файли:** `data/local/*`, mapper/repository changes, `ui/analytics/*`, navigation, schema export directory, DAO/database/repository tests.

**D. Dependencies:** Room runtime/ktx/compiler via KSP; navigation-compose. Chart: first-party Compose Canvas; Vico only if acceptance criteria demand richer graph.

**E. Permissions/API:** жодних нових permissions. DB work off main thread; exported Room schema committed.

**F. Codex:** schema, DAO, migrations, repository, chart, tests, generated schema verification.

**G. User:** generate movement data on AVD; inspect actual DB via Database Inspector; capture graph and table screenshots.

**H. Перевірка:** DAO ordering/limit; live graph updates; no main-thread DB access; process restart preserves records; retention bound; cloud sync reads pending Room rows.

**I. Артефакти:** ER diagram; MVVM/data-flow diagram; Entity/DAO listings; dynamic chart; Database Inspector rows; offline persistence proof.

### ЛР6 — TensorFlow Lite on-device inference

**A. Успадковано:** normalized sensor stream, Room history, background dispatchers, dashboard, actuator logic.

**B. Додати:** fixed-rate resampling; normalization matching training; circular buffer; `ActivityClassifier` interface; TFLite wrapper; model metadata/labels; inference on `Dispatchers.Default`; confidence and Unknown fallback; store prediction; compare model latency/accuracy on controlled sequences.

Рекомендована навчальна задача: 3 classes — `Still`, `Restless`, `Awake-like movement`. Реалістичний tensor contract варто зафіксувати до написання wrapper: наприклад float32 `[1, 50, 3]` для 5 seconds at 10 Hz, normalized X/Y/Z після gravity handling; output float32 `[1, 3]` probabilities/logits. Конкретна форма визначається реальною моделлю, не припущенням у коді.

Готова generic HAR model часто класифікує walking/running/sitting, має іншу sampling rate/axes preprocessing і не відповідає sleep-motion labels. Її можна використати лише після повної перевірки tensor contract і чесного перейменування сценарію. Для заданих класів краще власна мала 1D CNN/MLP, навчена на позначених записах із того самого emulator/device pipeline або контрольованих synthetic + recorded data. Teachable Machine для числових time-series не гарантовано підходить; згадка «за 5 хвилин» у методичці не є технічним доказом сумісності.

**C. Файли:** `app/src/main/assets/activity_model.tflite`, labels/metadata, `ml/*`, preprocessing contract tests, dashboard AI card, repository/entity extension + Room migration, benchmark/smoke tests.

**D. Dependencies:** зафіксований TFLite/LiteRT Interpreter. Support/Task library лише якщо модель та metadata реально цього потребують.

**E. Permissions/API:** нових permissions немає; native runtime/ABI packaging перевірити.

**F. Codex:** data collection/export code, training/conversion script за погодженим dataset, model inspection, wrapper/buffer/preprocessing, integration/tests. Codex може згенерувати `.tflite`, якщо доступні TensorFlow runtime і валідні дані; не може чесно гарантувати semantic accuracy без representative labeled data.

**G. User:** надати або зібрати/позначити representative windows для трьох класів; погодити tensor contract; за потреби запустити training environment/передати `.tflite`; вручну відтворити класи та оцінити predictions.

**H. Перевірка:** interpreter introspection збігається з declared dtype/shape; buffer не змішує windows; inference не блокує UI; invalid model/input має Error/Unknown; controlled samples змінюють prediction; latency measured.

**I. Артефакти:** model source/license/provenance; tensor table; labels/confusion matrix або хоча б controlled test matrix; assets screenshot; preprocessing/wrapper listing; AI Insights screenshots; inference timing.

### ЛР7 — Final Integration

**A. Успадковано:** усі окремо перевірені компоненти та artifacts ЛР1–6.

**B. Додати:** unified dashboard; last 5 records; persisted configurable smart rule; orchestration pipeline; offline queue/resync; deduplication/idempotency; noise filtering tuning; error states; end-to-end tests; profiler pass; retention/storage-full behavior; documentation.

**C. Файли:** dashboard consolidation, `AppContainer`, `SyncCoordinator`, optional constrained `Worker`, integration tests/fakes, final diagrams/test matrix/docs. Не робити «великий рефакторинг»: межі вже закладені раніше.

**D. Dependencies:** не додавати нові без конкретної прогалини. WorkManager допустимий тільки якщо потрібна гарантована sync після process death; Hilt/Koin не потрібні.

**E. Permissions/API:** сукупність попередніх; audit manifest на мінімальність. Background sensor tracking не заявляється: це вимагало б foreground service, notification та окремих UX/privacy рішень, яких немає у завданні.

**F. Codex:** orchestration, offline state machine, tests, failure injection, build/lint/test, diagrams/test-case drafts, performance instrumentation points.

**G. User:** AVD end-to-end run; вимкнення/відновлення network; Firebase Console validation; Android Profiler/Database Inspector/Network Inspector; screen recording; фактична test matrix.

**H. Перевірка:** повний шлях sensor -> preprocess -> ML -> decision -> Room -> cloud -> actuator -> mock BLE; restart/offline/restore; no duplicate uploads; bounded DB/logs; sensor listener cleanup; acceptable CPU/memory; graceful storage/network/hardware errors.

**I. Артефакти:** component/class diagram; business rules; full dashboard; inspectors/profiler; test matrix with actual results; short end-to-end video; final explanatory note.

## 5. Architecture evolution

| Етап | Система після checkpoint |
|---|---|
| ЛР1 | Sensor -> preprocessing -> ViewModel -> Compose UI |
| ЛР2 | ЛР1 + independent Mock BLE state machine and payload log |
| ЛР3 | ЛР2 + cloud abstraction/Firebase + sync state |
| ЛР4 | ЛР3 + DecisionEngine + local/manual/cloud actuator control |
| ЛР5 | ЛР4 + Room source of truth + repository + analytics |
| ЛР6 | ЛР5 + windowing/TFLite classification + persisted predictions |
| ЛР7 | ЛР6 + explicit orchestration, robust offline/resync, unified dashboard/tests |

Ключ до відсутності переписування: вже у ЛР1 sensor source віддає domain model через Flow; у ЛР2 BLE — інтерфейс; у ЛР3 cloud — інтерфейс; у ЛР4 decision logic — pure Kotlin. ЛР5 замінює внутрішню persistence/sync реалізацію, але не UI contracts. ЛР6 підключається до того самого preprocessed stream.

## 6. CODEX CAN DO

- Налаштувати Kotlin, Compose, version catalog, Manifest і сумісні Gradle plugins.
- Створити та рефакторити Kotlin/Compose code, MVVM state, manual DI.
- Реалізувати SensorManager lifecycle wrapper, filtering, threshold/hysteresis.
- Реалізувати Mock BLE profile/state machine/logging без hardware.
- Реалізувати Firebase integration після надання `google-services.json`.
- Реалізувати DecisionEngine, virtual/Android actuator wrappers та capability fallback.
- Реалізувати Room schema/DAO/repository/migrations і Compose chart.
- Реалізувати circular buffer, TFLite wrapper та перевірку tensor shapes.
- Створити training/conversion tooling, якщо буде валідний dataset і доступне середовище.
- Написати unit, DAO, UI та integration tests із fakes.
- Виконати Gradle build/test/lint і виправити code/config failures.
- Підготувати Mermaid/текстові схеми, таблиці та перелік матеріалів для звіту.

Codex не повинен вигадувати результати ручного тестування, screenshots, profiler metrics або ML accuracy.

## 7. USER MUST DO: зовнішні/manual checkpoints

| ЛР | Коли Codex зупиняється | Дія користувача | Ознака виконання | Що передати Codex |
|---|---|---|---|---|
| 0 | Якщо JDK 25 не доступний Gradle | В Android Studio вибрати/встановити сумісний Gradle JDK 25 або відновити Foojay download | `gradlew ...` проходить toolchain resolution | Вивід `java -version`, Gradle JDK path або нова помилка |
| 1 | Після green build/tests і готового APK | Запустити AVD API 31+, Extended Controls -> Virtual Sensors; змінити pose/movement | X/Y/Z та state змінюються, pause зупиняє updates | Screenshots, модель/API AVD, спостережені значення/аномалії |
| 2 | Після готового mock flow | Натиснути scan, дочекатись device, connect, send | Послідовність станів і Logcat payload видимі | Screenshots UI/Logcat |
| 3 | До реальної Firebase збірки | Створити Firebase project/app, Realtime DB; скачати правильний `google-services.json`; налаштувати rules | package name збігається, config у `app/`, Console доступна | Файл config локально, database URL/region, rules без секретів |
| 3 | Після інтеграції | Рухати virtual sensor, перевірити Console/Network Inspector; вимкнути/увімкнути network | Online row, Pending offline, sync after restore | Screenshots і фактичні результати |
| 4 | Після command listener/actuator build | Змінити command у Firebase Console; перевірити virtual lamp/vibration/flash capability | UI реагує один раз, ack з'являється; unsupported flash не crash | Command/ack screenshots, інформація про AVD/hardware feedback |
| 5 | Після Room/Analytics build | Наповнити даними; відкрити Database Inspector | Rows відповідають графіку та переживають restart | Inspector + graph screenshots |
| 6 | До фінальної ML інтеграції | Надати/зібрати labeled data або валідну `.tflite`; підтвердити labels, sampling, input/output | Model opens; відомі exact shape/dtype/normalization/license | Model, labels, provenance, tensor contract або dataset |
| 6 | Після інтеграції | Відтворити контрольовані рухи й оцінити predictions | Є фактична test/confusion table, не лише screenshots | Results, screenshots, помилкові класифікації |
| 7 | Після automated integration green | Провести offline/resync, inspectors, тривалий Profiler run, screen recording | Дані resync без duplicates; listener/memory stable; повний demo записаний | Metrics/screenshots/video/test matrix |

## 8. Risk analysis

| Ризик | Рівень | Наслідок | Мінімізація |
|---|---|---|---|
| JDK 25/toolchain download недоступний | HIGH зараз | Неможлива відтворювана build verification | Phase 0: налаштувати локальний Gradle JDK 25; зафіксувати `gradlew --version`; не змінювати версії навмання. |
| AGP 9.4.1 / Gradle 9.6 / Kotlin/Compose/KSP compatibility | HIGH | Plugin resolution/compile failures | Підбирати версії як сумісний набір, фіксувати catalog, після кожної dependency запускати build/test/lint. |
| Emulator accelerometer limitations/noise | MEDIUM | Нестабільний threshold, нереалістичні patterns | Feature detection, filtering, resampling, hysteresis, synthetic fake source для tests; ручна AVD validation. |
| Тема називає «фази сну» | HIGH методологічний | Необґрунтовані медичні висновки | Усюди називати результат movement/activity classes; явно описати limitations. |
| Mock BLE вимоги суперечать permission list методички | MEDIUM | Зайві dangerous permissions або питання на захисті | Не викликати real Bluetooth API; пояснити permissions real vs mock; додати декларації лише на пряме рішення викладача. |
| Firebase account/config/rules | HIGH | Build config відсутній або permission denied/data exposed | User checkpoint; separate dev DB; restrictive rules; не комітити секрети; validate package/app. |
| Firebase offline queue до появи Room | MEDIUM | Нечітке Pending/duplicate behavior | ЛР3: stable IDs + Firebase persistence; ЛР5: Room sync status and idempotent keys become source of truth. |
| Camera/flashlight на AVD | HIGH | Hardware demo неможливий | Virtual actuator is acceptance path; `FEATURE_CAMERA_FLASH` guard; physical device optional, не обіцяти status-bar effect. |
| Vibration на AVD | MEDIUM | Feedback не відчувається | UI actuator state + logs; real device optional. |
| Room/KSP versions | MEDIUM | Codegen failure | Compatibility check, schema export, migration/DAO tests, no kapt+KSP mixture. |
| Third-party Compose chart | MEDIUM | API/Compose incompatibility | Для 50 points використовувати Canvas; додати Vico лише за потреби. |
| Невідомий TFLite tensor contract | HIGH | Runtime shape/type crash або безглузді predictions | Model-first introspection; exact shape/dtype/normalization tests; fail-fast metadata validation. |
| Немає придатної sleep-motion model/data | HIGH | Неможлива чесна classification validation | Зібрати власні labeled windows; мала documented model; Unknown threshold; не заявляти medical accuracy. |
| Sensor callbacks записуються в Room надто часто | MEDIUM | Ріст DB, I/O, chart overload | Sampling/significant delta, last 50 chart, retention/pruning, storage-error handling. |
| Cloud listener/sensor listener leaks | MEDIUM | Battery/memory leak, duplicate events | Lifecycle ownership, explicit remove/unregister, tests, Profiler run. |
| Network availability трактована як sync success | MEDIUM | False green status | Розрізняти connectivity, queued, acknowledged write і error. |
| Duplicate resync records | MEDIUM | Некоректна analytics/cloud data | Stable UUID key/upsert, sync state, retries idempotent. |
| Git root охоплює user profile | HIGH operational | Випадковий commit сторонніх/секретних файлів | Окремий repo у project root або path-scoped git operations; перевірка staged files. |

## 9. Точний порядок реалізації та checkpoints

### Phase 0 — Build baseline

1. Вирішити JDK 25/toolchain.
2. Записати `gradlew --version`.
3. Запустити `:app:assembleDebug`, `testDebugUnitTest`, `lintDebug` на незміненому baseline.
4. Визначити Git boundary.
5. Лише після green baseline додати Kotlin/Compose як один сумісний version set.

**Checkpoint 0:** configuration, empty app build, tests і lint green; версії зафіксовані.

### Phase 1 — ЛР1

Domain sensor model -> Android source -> preprocessing/threshold tests -> ViewModel -> Compose UI -> lifecycle/UI tests -> AVD manual validation -> report artifacts.

**Checkpoint 1:** build/test/lint green; manual Virtual Sensors proof; screenshots saved. Зупинитися.

### Phase 2 — ЛР2

BLE contracts/profile -> deterministic mock scanner/connector -> state tests -> UI/log -> manual end-to-end -> artifacts.

**Checkpoint 2:** invalid transitions tested; scan/connect/send demonstrated. Зупинитися.

### Phase 3 — ЛР3

Cloud contract/fake -> Firebase user setup checkpoint -> integration -> throttling/pending/retry -> online/offline manual test -> artifacts.

**Checkpoint 3:** confirmed Console write and pending/reconnect behavior. Зупинитися.

### Phase 4 — ЛР4

Pure DecisionEngine/tests -> actuator abstraction/fake -> Android/virtual actuators -> manual controls -> cloud command/ack -> capability/failure tests -> manual demo.

**Checkpoint 4:** automatic, manual and cloud paths separately demonstrated. Зупинитися.

### Phase 5 — ЛР5

Schema/DAO/tests -> repository/local source of truth -> migration of sync path -> Analytics/Canvas chart -> restart/Inspector verification -> artifacts.

**Checkpoint 5:** DB persistence, last N and live chart proven. Зупинитися.

### Phase 6 — ЛР6

Freeze labels/tensor contract -> obtain/train/inspect model -> preprocessing/buffer tests -> wrapper -> off-main integration -> Room migration -> controlled manual evaluation -> artifacts.

**Checkpoint 6:** model provenance and tensor contract documented; runtime and controlled predictions verified. Зупинитися.

### Phase 7 — ЛР7

Wire orchestration -> unified dashboard -> configurable rule -> offline/resync/dedup tests -> fault injection -> profiler/inspectors -> complete artifacts/video.

**Checkpoint 7:** automated suite green; full manual test matrix filled with actual results; no fabricated metrics; final demo complete.

## 10. Acceptance gates common to every laboratory

Лабораторна не завершена, якщо відсутній хоча б один пункт:

1. `assembleDebug` успішний.
2. Unit/instrumentation tests, які відповідають доданій логіці, успішні.
3. `lintDebug` не має неприйнятих errors.
4. Немає lifecycle leak/crash у негативному сценарії.
5. Ручний сценарій на AVD виконаний користувачем.
6. Screenshots/logs/diagrams/listings для звіту збережені.
7. Обмеження та фактичні результати описані без перебільшення.
8. Перехід до наступної ЛР явно підтверджений користувачем.

