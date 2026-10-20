# Firebase Remote Control CLI

Ця утиліта записує бажаний стан актуаторів у Firebase Realtime Database. Успішний запис і readback не підтверджують, що телефон фізично ввімкнув ліхтарик або вібрацію. Для фізичного підтвердження потрібні телефон і Android Logcat.

## Контракт

- Project ID: `sleeptrackersimulator`
- Database URL: `https://sleeptrackersimulator-default-rtdb.europe-west1.firebasedatabase.app`
- Path: `/users/{UID}/control/sleep-tracker-simulator`
- Поля: `flashlightEnabled` і `vibrationEnabled`, тип Boolean.

CLI ніколи не записує `/users/{UID}` повністю, не торкається `sessions` і не видаляє control node. Окремі команди використовують точковий `update`; `reset` атомарно оновлює лише два control-поля; `init` використовує транзакцію.

## 1. Перевірка Python у Windows CMD

```bat
py --version
```

Потрібен Python 3.11 або новіший.

## 2. Встановлення залежностей

Щоб після одноразового встановлення використовувати точну коротку команду `py tools\\firebase_control.py ...` у нових вікнах CMD:

```bat
cd /d C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator
py -m pip install --user -r tools\requirements-firebase-control.txt
```

Ізольований варіант через віртуальне середовище:

```bat
cd /d C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator
py -m venv tools\.venv
tools\.venv\Scripts\python.exe -m pip install --upgrade pip
tools\.venv\Scripts\python.exe -m pip install -r tools\requirements-firebase-control.txt
```

Після цього використовуйте `tools\.venv\Scripts\python.exe` замість `py` у командах нижче або спочатку активуйте середовище командою `tools\.venv\Scripts\activate.bat`.

## 3. Авторизація

`firebase login` не є Application Default Credentials для Python Admin SDK.

Варіант A — ADC через Google Cloud CLI:

```bat
gcloud auth application-default login
gcloud auth application-default set-quota-project sleeptrackersimulator
```

Обліковий запис повинен мати доступ до Realtime Database проєкту. Не видавайте роль Owner. Для read-only `doctor` достатній окремий обліковий запис із правом читання RTDB; для команд запису потрібен окремий технічний обліковий запис із правом запису RTDB. Firebase Admin SDK може обходити Database Rules, тому цей обліковий запис слід використовувати лише для цієї утиліти та не передавати іншим користувачам.

Варіант B — JSON окремого service account, створений адміністратором проєкту:

```bat
set GOOGLE_APPLICATION_CREDENTIALS=C:\secure\sleeptracker-control-service-account.json
```

Не кладіть JSON у репозиторій, `tools`, Android resources/assets або APK. CLI не створює й не завантажує private key.

## 4. Налаштування цільового UID

UID належить конкретній інсталяції застосунку й може змінитися після очищення даних або перевстановлення. Перед тестом звірте його на телефоні або у Firebase Authentication.

Для поточного вікна CMD:

```bat
set SLEEPTRACKER_TARGET_UID=1awU9VJr6tXyWcN3LWmXT6MnbIC3
```

Або передавайте UID кожній команді:

```bat
py tools\firebase_control.py --uid 1awU9VJr6tXyWcN3LWmXT6MnbIC3 status
```

Опційні змінні:

```bat
set SLEEPTRACKER_FIREBASE_PROJECT_ID=sleeptrackersimulator
set SLEEPTRACKER_FIREBASE_DATABASE_URL=https://sleeptrackersimulator-default-rtdb.europe-west1.firebasedatabase.app
```

## 5. Діагностика і початкова ініціалізація

`doctor` лише читає дані:

```bat
py tools\firebase_control.py doctor
```

Якщо control node відсутній або бракує поля:

```bat
py tools\firebase_control.py init
```

Для автоматизованого запуску без питання:

```bat
py tools\firebase_control.py --yes init
```

`init` не перезаписує наявні Boolean-значення. При неправильному типі він завершується помилкою без автоматичного виправлення.

## 6. Команди керування

```bat
py tools\firebase_control.py flashlight on
py tools\firebase_control.py flashlight off
py tools\firebase_control.py vibration on
py tools\firebase_control.py vibration off
py tools\firebase_control.py status
py tools\firebase_control.py reset
```

Розширена діагностика без credentials або токенів:

```bat
py tools\firebase_control.py --verbose flashlight on
```

## 7. Зіставлення з Android Logcat

У новій системі діагностики Android використовуйте фільтр:

```text
tag:SleepTracker-Cloud | tag:SleepTracker-Actuators
```

Очікувана послідовність після CLI-команди:

```text
SleepTracker-Cloud: operation=remoteControlEvent result=SUCCESS source=FIREBASE ...
SleepTracker-Actuators: operation=setFlashlight result=STARTED source=REMOTE enabled=true
SleepTracker-Actuators: operation=androidApiCall result=SUCCESS physicalEffectConfirmed=false ...
```

Останній рядок підтверджує лише успішний виклик Android API, не фізичний ефект.

## 8. Типові помилки

- `firebase-admin is not installed`: встановіть requirements у вибраному Python environment.
- `credentials are unavailable`: виконайте ADC login або задайте `GOOGLE_APPLICATION_CREDENTIALS`.
- `permission denied`: перевірте IAM доступ технічного облікового запису та Project ID. Не послаблюйте Database Rules.
- `credentials resolve to ...`: активні ADC належать іншому Google Cloud project; виберіть правильний обліковий запис або service account.
- `Control node does not exist`: виконайте `init` після перевірки UID.
- `invalid type`: перевірте вузол вручну; CLI навмисно не перезаписує конфліктні дані.
- `timed out`: перевірте мережу, URL бази й повторіть команду один раз.
- CLI записав значення, але телефон не реагує: застосунок має бути відкритий у foreground, listener має бути connected, а UID має відповідати поточній інсталяції. Перевірте два Android Logcat-теги вище.

## 9. Локальні перевірки

```bat
py -m py_compile tools\firebase_control.py tools\tests\test_firebase_control.py
py -m unittest discover -s tools\tests -v
py tools\firebase_control.py --help
git diff --check
```
