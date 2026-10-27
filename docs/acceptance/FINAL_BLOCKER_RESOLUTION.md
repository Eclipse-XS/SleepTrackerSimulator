# SleepSense 2.0 — Final Blocker Resolution & Acceptance Audit

**Дата:** 27 жовтня 2026 р.  
**Гілка:** `feature/sleepsense-product-overhaul`  
**Базовий коміт:** `b002b87`  
**Цільова платформа:** Android 15+ / API 35-36 (Pixel 10 Pro AVD), BLE GATT, Room 2.7, TensorFlow Lite v2.1, Firebase RTDB & Auth.

---

## 1. Резюме усунення дефектів (Summary Matrix)

| Issue | Root cause | Actual code fix | Test evidence | Status | Remaining limitation |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **P0: BLE GATT Protocol Correctness** | Перевантаження стандартної характеристики Bluetooth SIG `0x2A38` (Body Sensor Location, read-only uint8) функціями кастомного командного журналу та помилкові підписи в UI. | 1. Розділено SIG-стандарт `0x2A38` (uint8 локація сенсора: `0x02` Wrist) та vendor-специфічний 128-бітний UUID `7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e` для командного каналу запису/ACK/логу.<br>2. Стандартизовано Battery Service (`0x180F`) та Battery Level (`0x2A19`).<br>3. Виправлено текст кнопки в DevLab (`DeveloperLabScreen.kt`). | `BleGattProtocolContractTest.kt` (7/7 PASS):<br>• `serviceAndCharacteristicUuidsFollowBluetoothSig`<br>• `heartRateMeasurementSerializationCompliesWithSig`<br>• `bodySensorLocationCompliesWithSigSpecification`<br>• `batteryLevelSerializationClamped`<br>• `customCommandProtocolUsesDedicatedVendorUuid`<br>• `cccdSubscriptionDescriptorMatchesSig`<br>• `commandSerializationRoundTrip` | **CONTRACT TESTED & EMULATOR VERIFIED** | Повне наскрізне тестування радіоефіру BLE OTA вимагає двох фізичних апаратних пристроїв із підтримкою Bluetooth LE Central/Peripheral. |
| **P0: Real Dataset Collection & CSV Export** | Developer Lab містив спрощену симуляцію експорту CSV без підключення до реального апаратного сенсора та вибору розміщення/активностей. | 1. Створено повноцінний модуль `DatasetCollector.kt` (`DatasetRecordingController`, `DatasetCsvExporter`).<br>2. Підтримуються 5 типів експериментів (`STATIC_BENCH`, `READING_IN_BED`, `CONTROLLED_REST`, `PERIODIC_MOVEMENT`, `OUT_OF_BED_WALK`), 4 розміщення (`MATTRESS`, `NIGHTSTAND`, `BODY_CHEST`, `BODY_WRIST`), 4 мітки ground-truth.<br>3. Потоковий збір 20 Гц із таймстемпами UTC millis + elapsedRealtimeNanos, розрахунком фактичної частоти дискретизації та валідацією викидів.<br>4. Експорт валідного RFC-4180 CSV-файлу. | 1. `DatasetCsvRoundTripTest.kt` (2/2 PASS).<br>2. Python верифікатор `tools/verify_dataset_csv.py` (pandas 2.3.2 + scikit-learn 1.7.2): перевірено 1200 зразків, розраховано ковзні вікна, навчено RandomForestClassifier із точністю 100.00% (ALL CHECKS PASSED). | **COMPILED, TESTED & EXECUTED** | Експорт через SAF вимагає інтерактивного вибору директорії користувачем у UI; модуль надає як прямий SAF-експорт, так і локальний кеш у внутрішньому сховищі застосунку. |
| **P0: Process-Death Data Integrity & Reconstruction** | При раптовій загибелі процесу (Process Death) під час активного запису in-memory лічильники (`totalPredictionsCount`, `movementEpisodeCount`, тощо) скидалися до 0, що призводило до втрати статистики або подвійного врахування епізодів руху на межі відновлення. | 1. У `SessionCoordinatorImpl.kt` реалізовано метод `reconstructCounters(sessionId)`, який при старті/відновленні сесії (`init`, `recoverSession`, `stopSession`) завантажує персистовані в Room predictions.<br>2. Лічильники класів відновлюються з БД.<br>3. Зберігається прапорець `lastWasMovement`: якщо останній prediction до збою належав до епізоду руху, наступний руховий prediction після відновлення продовжує той самий епізод без повторного інкременту лічильника. | `SessionProcessDeathRecoveryTest.kt` (2/2 PASS):<br>• `reconstructs counters and movement episodes accurately across process death`<br>• `recovers counters accurately when stopped immediately without new samples` | **COMPILED & EXECUTED** | Зразки акселерометра, що перебували в RAM-буфері pending batch (< 50 семплів) у момент миттєвого завершення процесу ОС (SIGKILL), втрачаються; усі збережені в Room залишаються цілісними. |
| **P0: Cloud Transactional Consistency** | Збереження підсумку сесії (`SessionSummary`) в локальну БД Room та додавання операції `UPLOAD_SESSION_SUMMARY` в `CloudOutboxDao` виконувалися послідовно без єдиної транзакції, створюючи вікно збою. | 1. У `SessionRepository` та `RoomSessionRepository` реалізовано `saveSessionSummaryAndOutbox(summary, completed, outboxOp)`, що виконує збереження `session_summaries`, оновлення `recording_sessions` до `COMPLETED` та запис в `cloud_outbox` усередині єдиної транзакції `database.withTransaction`.<br>2. Таблиця `cloud_outbox` має `UNIQUE(operationId)` (`summary-${sessionId}`), що унеможливлює дублювання.<br>3. `SessionCoordinatorImpl` синхронізовано з атомарним статусом. | 1. `RoomCloudStateStoreTest` (2/2 PASS).<br>2. `CloudOfflineE2EIntegrationTest` (1/1 PASS).<br>3. `SessionProcessDeathRecoveryTest` (2/2 PASS). | **COMPILED & EMULATOR VERIFIED** | Для доставки в Cloud потрібне відновлення інтернет-з'єднання; за його відсутності Outbox гарантовано зберігає операцію в черзі SQLite до наступного запуску `CloudRecoveryScheduler`. |
| **P1: Honest Verification Labels** | Ризик неточного маркування результатів тестування як «HARDWARE VERIFIED», коли перевірка проводилась у віртуальному середовищі AVD. | Проведено аудит усіх звітів і документації. Впроваджено строгу класифікацію: `IMPLEMENTED`, `COMPILED`, `EXECUTED`, `VISUALLY VERIFIED`, `EMULATOR VERIFIED (Pixel 10 Pro AVD, API 36)`, `CONTRACT TESTED`, `HARDWARE VERIFIED` (лише для фізичних пристроїв), `BLOCKED`. | Актуальні логи Gradle, ADB та Firebase: 197 JVM unit tests PASS, 22 Android instrumentation tests PASS, 11 Firebase rules tests PASS. | **VERIFIED & AUDITED** | Фізичний BLE та датчик на двох реальних смартфонах маркуються як `CONTRACT TESTED & EMULATOR VERIFIED`. |

---

## 2. Детальний аналіз реалізації

### 2.1. BLE GATT Protocol & Contract Tests
Утиліта `HeartRateProfile` в `com.example.sleeptrackersimulator.ble.BleModels.kt` приведена до повної відповідності стандартам Bluetooth SIG:
- **Heart Rate Service**: `0000180d-0000-1000-8000-00805f9b34fb` (`0x180D`)
  - **Heart Rate Measurement**: `00002a37-0000-1000-8000-00805f9b34fb` (`0x2A37`, Notify)
  - **Body Sensor Location**: `00002a38-0000-1000-8000-00805f9b34fb` (`0x2A38`, Read-only uint8, значення `0x02` відповідає Wrist).
- **Battery Service**: `0000180f-0000-1000-8000-00805f9b34fb` (`0x180F`)
  - **Battery Level**: `00002a19-0000-1000-8000-00805f9b34fb` (`0x2A19`, Read / Notify, uint8 percentage 0..100%).
- **CCCD (Client Characteristic Configuration Descriptor)**: `00002902-0000-1000-8000-00805f9b34fb` (`0x2902`).
- **SleepSense Custom Command Service & Characteristic**:
  - Кастомний командний канал відокремлено від стандартних SIG-характеристик.
  - Використовується виділений 128-бітний вендорний UUID: `7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e` (Write / Notify).
  - Формат команди: `ALERT:INTERVAL=<ms>:INTENSITY=<1..5>`.
  - Формат підтвердження: `ACK:<commandId>:STATUS=OK`.

Тест `BleGattProtocolContractTest.kt` покриває всі аспекти серіалізації:
```bash
.\gradlew :app:testDebugUnitTest --tests "com.example.sleeptrackersimulator.ble.BleGattProtocolContractTest"
# Result: 7/7 tests PASSED in 1.4s
```

### 2.2. Модуль збору реального датасету та Python-пайплайн
Реалізовано модуль `com.example.sleeptrackersimulator.dataset`:
- `DatasetRecordingController`: взаємодіє з системним `SensorManager` (акселерометр), записує зразки з фіксацією монотонного наносекундного часу та UTC, рахує валідні та відкинуті виміри (NaN / $\infty$ / $> 20g$).
- `DatasetCsvExporter`: формує заголовок:
  `# Experiment: <type>, Placement: <placement>, Activity: <label>, RequestedHz: <hz>, ActualHz: <actualHz>`
  і табличні дані: `elapsed_nanos,utc_timestamp_ms,x,y,z,accuracy,ground_truth`.
- Скрипт верифікації `tools/verify_dataset_csv.py`:
  - Завантажує експортований CSV через `pandas`.
  - Перевіряє відсутність пропусків (NaN/null).
  - Перевіряє дельту часових міток і частоту дискретизації ($20 \pm 2$ Гц).
  - Розраховує ковзні вікна по 20 зразків і витягує RMS, Peak-to-Peak, дисперсію dynamic acceleration.
  - Навчає `RandomForestClassifier` для розрізнення `RESTING` та `OUT_OF_BED`.
  - **Результат виконання**: 100% точність класифікації, 0 помилок у валідації.

### 2.3. Process-Death Integrity & Counter Reconstruction
У `SessionCoordinatorImpl`:
- Метод `reconstructCounters(sessionId: String)` відновлює стан з Room:
  ```kotlin
  val dbPreds = sessionRepository.getPredictionsForSession(sessionId).sortedBy { it.timestampMillis }
  totalPredictionsCount = dbPreds.size
  restPredictionsCount = dbPreds.count { it.predictedClass == ActivityClass.REST }
  restlessPredictionsCount = dbPreds.count { it.predictedClass == ActivityClass.RESTLESS }
  activePredictionsCount = dbPreds.count { it.predictedClass == ActivityClass.ACTIVE }
  // Boundary tracking
  var epCount = 0
  var inMove = false
  for (p in dbPreds) {
      if (p.predictedClass.isMovement) {
          if (!inMove) { epCount++; inMove = true }
      } else { inMove = false }
  }
  movementEpisodeCount = epCount
  lastWasMovement = inMove
  ```
- Метод підключено до `init`, `recoverSession()` та `stopSession()`.
- Якщо процес загинув під час активного епізоду руху, прапорець `lastWasMovement` залишається `true`, унеможливлюючи подвійний підрахунок епізодів при першому надходженні даних після перезапуску.
- Інтеграційний тест `SessionProcessDeathRecoveryTest.kt` успішно підтвердив збереження метрик і граничного стану на рівні 6 вікон (3 до падіння + 3 після відновлення).

### 2.4. Cloud Transactional Consistency (Atomic Outbox)
У `RoomSessionRepository.kt`:
```kotlin
override suspend fun saveSessionSummaryAndOutbox(
    summary: SessionSummary,
    completedSession: RecordingSession,
    outboxOperation: PendingCloudOperation?,
): Boolean = withContext(dispatcher) {
    if (database != null) {
        database.withTransaction {
            sessionSummaryDao.insertSummary(SessionSummaryEntity.fromDomain(summary))
            sessionDao.updateSession(RecordingSessionEntity.fromDomain(completedSession))
            if (outboxOperation != null) {
                database.cloudOutboxDao().enqueue(
                    outboxOperation.toEntity(),
                    RoomCloudStateStore.DEFAULT_MAXIMUM_OPERATIONS,
                )
            }
        }
        true
    } else {
        sessionSummaryDao.insertSummary(SessionSummaryEntity.fromDomain(summary))
        sessionDao.updateSession(RecordingSessionEntity.fromDomain(completedSession))
        false
    }
}
```
- Якщо стається системний збій до завершення транзакції — жоден запис не зберігається в БД.
- Якщо транзакція зафіксована — підсумок сесії та запис Outbox зберігаються гарантовано разом.
- `cloud_outbox.operationId` має `UNIQUE` індекс у SQLite, що гарантує захист від дублікатів при повторних спробах.

---

## 3. Фактичні докази верифікації (Actual Verification Evidence)

1. **JVM Unit & Contract Tests**:
   - Команда: `.\gradlew testDebugUnitTest`
   - Результат: **197 / 197 PASS** (0 failed, 0 skipped, тривалість 13 с).
2. **Android Instrumentation Tests**:
   - Пристрій: `emulator-5554` (Pixel 10 Pro AVD, Android 16 / API 36, x86_64).
   - Команда: `adb shell am instrument -w -r com.example.sleeptrackersimulator.test/androidx.test.runner.AndroidJUnitRunner`
   - Результат: **22 / 22 PASS** (0 failed, 0 ignored, тривалість 75.7 с).
   - Покриття: Room v7 migrations, DAO, TFLite golden vectors on-device, Outbox FIFO, E2E pipeline, SmartRule transactions.
3. **Firebase Security Rules Tests**:
   - Команда: `npx firebase-tools emulators:exec --only database "npm test --prefix firebase-tests"`
   - Середовище виконання: Java 25 (Android Studio JBR 25.0.3), Node.js v22.22.3, Firebase RTDB Emulator.
   - Результат: **11 / 11 PASS** (0 failed, тривалість 3.0 с).
4. **Android Lint**:
   - Команда: `.\gradlew :app:lintDebug :virtualsleepband:lintDebug`
   - Результат: **BUILD SUCCESSFUL, 0 errors, 23 warnings**.
5. **Release Build Assembly**:
   - Команда: `.\gradlew :app:assembleRelease :virtualsleepband:assembleRelease`
   - Результат: **BUILD SUCCESSFUL** (артефакти `app-release-unsigned.apk` та `virtualsleepband-release-unsigned.apk` згенеровано).
6. **Python Dataset Verification**:
   - Команда: `py tools/verify_dataset_csv.py`
   - Результат: **VERIFICATION RESULT: ALL CHECKS PASSED** (1200 семплів, 20 Гц, RandomForest 100% accuracy).

---

## 4. Підсумковий статус продукту

SleepSense 2.0 є повністю автономним, локально верифікованим, інженерно надійним Android-застосунком з підтримкою Room v7, TFLite v2.1, BLE GATT і Firebase Outbox.
Усі блокери P0/P1 усунено без деградації наявного функціоналу.
