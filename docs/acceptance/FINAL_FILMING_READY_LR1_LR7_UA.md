# SleepSense 2.0 RC — сценарій фінальної зйомки ЛР1–ЛР7

Цей сценарій стосується APK `2.0-RC1`. Перед зйомкою на Phone A встановлюється `SleepSense-2.0-RC-debug.apk`, на Phone B — `VirtualSleepBand-RC-debug.apk`. Старі APK не використовуються.

## Спільна підготовка

- Phone A: надати Physical activity, Notifications, Nearby devices/Bluetooth і Camera лише під час відповідного запиту.
- Phone B: надати Bluetooth advertise/connect permissions, увімкнути Bluetooth, запустити autonomous simulation.
- Для Firebase-демонстрації потрібен APK, локально зібраний з авторизованим, не закоміченим `app/google-services.json`. CI APK використовує demo-конфігурацію і не є доказом реального cloud upload.
- Перед відео очистити лише demo data через Developer Lab → Room → Reset demo. Реальні записи не видаляти.

## ЛР1 — Sensor Input

- Шлях: Settings → Open Developer Lab → `Sensors (L1)`.
- Дії: покласти Phone A нерухомо, потім плавно повернути й кілька разів перемістити.
- У кадрі: live X/Y/Z/динамічна величина, hardware state, зміна STILL/MOVEMENT.
- Тривалість: 30–45 секунд.
- Потрібно: Phone A. Android Studio окремо показує структуру коду та lifecycle listener; відео телефона це не замінює.

## ЛР2 — BLE та Mock IoT

- Mock: Legacy Lab View → scan → вибрати virtual/mock device → connect → показати BPM, packet log, command/read/write.
- Real companion: Phone B → Virtual SleepBand → Automatic scenario/advertising; Phone A → Devices → `REAL BLE` → Scan → VirtualSleepBand → Connect.
- У кадрі: явна позначка режиму, connection state, змінний BPM, simulated provenance, disconnect/reconnect.
- Тривалість: 60–90 секунд.
- Потрібно: Phone A + Phone B. Якщо другого телефона немає, real BLE фрагмент не оголошується виконаним.

## ЛР3 — Firebase Cloud Sync

- Шлях: Legacy Lab View → Cloud/monitoring; Developer Lab → `Cloud (L3)` для правил і діагностики.
- Дії: почати monitoring online, показати synchronized; вимкнути мережу, створити подію/summary, показати pending outbox; відновити мережу й дочекатися synchronized.
- У кадрі: LOCAL/PENDING/ERROR/SYNCED, pending count, retry/recovery.
- Тривалість: 60–90 секунд.
- Потрібно: Phone A, мережа, авторизований Firebase build, Firebase Console для окремого комп’ютерного доказу. CI demo APK не використовується як real-cloud доказ.

## ЛР4 — Actuators

- Шлях: Settings → Developer Lab → `Actuators (L4)`.
- Дії: Flashlight ON → показати фізичний LED → Flashlight OFF; повторити; Vibration Pulse; Persistent Vibration ON → OFF; Automatic Control ON/OFF; Emergency OFF.
- У кадрі: requested state, API result, TorchCallback observed state, physical confirmation виконується самим відео.
- Тривалість: 45–60 секунд.
- Потрібно: Phone A. OFF та Emergency OFF мають залишатися доступними незалежно від automatic/remote state.

## ЛР5 — Room і Analytics

- Шлях: Developer Lab → `Room (L5)`.
- Дії: Generate → Import → Verify; History → Demo; відкрити session details; Insights → Demo; перезапустити застосунок; повернутися в History; повторний Import; Reset demo.
- У кадрі: рівно 90 SIMULATED sessions, integrity verified, duration/coverage/movement/low-motion/BPM, chart, persistence, відсутність дублікатів, real history після reset.
- Тривалість: 90–120 секунд.
- Потрібно: Phone A. Android Studio Database Inspector і schema/migration evidence записуються окремо на комп’ютері.

## ЛР6 — On-device TFLite

- Шлях: Legacy Lab View → live ML panel; Developer Lab → `ML (L6)`.
- Дії: нерухомий телефон → контрольовані дрібні рухи → інтенсивні рухи.
- У кадрі: REST/RESTLESS/ACTIVE, confidence/probabilities, inference latency, локальне виконання.
- Тривалість: 45–60 секунд.
- Потрібно: Phone A. Не використовувати формулювання «медична діагностика» або «98% real-world accuracy».

## ЛР7 — End-to-end Smart System

- Шлях: Today → Start Recording; Legacy Lab View/Smart Automation Rules; після Stop → History/Insights.
- Дії: почати recording, створити контрольований рух, показати ML state і automatic actuator event, перевірити Room/History, показати cloud/outbox state; за наявності Phone B повторити з BLE HR source.
- У кадрі: sensor → preprocessing → TFLite → rule → actuator → Room → cloud/outbox → History/Insights; provenance джерела.
- Тривалість: 2–3 хвилини.
- Потрібно: Phone A; Phone B для BLE-гілки; Firebase лише для авторизованого build.

## Розподіл відповідальності

- Друг знімає лише фізично спостережувані ефекти: LED, vibration, рух телефона, два телефони BLE, vendor screen recorder.
- Студент готує Android Studio project structure, code listings, Database Inspector, Firebase Console, CI/test evidence і звітні матеріали.
- Фізичне спостереження не замінюється emulator/API success; комп’ютерні артефакти не замінюються відео телефона.
