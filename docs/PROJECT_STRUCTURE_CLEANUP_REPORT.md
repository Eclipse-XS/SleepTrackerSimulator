# SLEEPSENSE 2.0 / SLEEPTRACKERSIMULATOR
## Фінальний інженерний звіт аудиту структури та академічного впорядкування репозиторію

**Дата ревізії:** 27 жовтня 2026 року  
**Проєкт:** `SleepTrackerSimulator` (Комерційна назва: **SleepSense 2.0**)  
**Студент:** Гончаров Вадим, група ОІ-43  
**Кафедра:** Автоматизованих систем управління, НУ «Львівська політехніка»  
**Дисципліна:** Програмування мобільних смарт-систем (ПМСС / МПСС)  
**Поточна гілка:** `feature/sleepsense-product-overhaul` (базований на `78ba612`)  

---

## 1. Огляд структури проєкту: До та Після (Before / After)

### Стан ДО впорядкування
- Репозиторій містив понад **18 700 службових файлів AI-агентів (Codex/Gemini)** загальним обсягом понад **931 МБ** у каталогах `.codex-artifacts/`, `.codex-doc/`, `.codex-docx-qa/`, `.codex-pptx/`. Більшу частину цього обсягу складали дубльовані пакети `node_modules` (713 МБ) бібліотеки `@oai/artifact-tool`, що використовувалася агентом для одноразової компіляції слайдів презентації, а також понад 1400 растрових PNG-зображень сторінок звітів, згенерованих для візуального QA.
- У корені проєкту знаходились тимчасові та застарілі файли: лог емулятора `database-debug.log`, початковий план розробки `PLAN.md` (від 28.09.2026, до початку реалізації ЛР 1) та специфікація передачі контексту `LAB2_HANDOFF.md` (від 29.09.2026).
- Каталог `tools/` був захаращений 36 проміжними файлами QA-перевірки формул OMML/MathML, знімками екранів Word-документів та сирими JSON-дампами (`tools/qa_*`, `tools/*.json`, `tools/*.png`), а також прихованим lock-файлом Microsoft Word `word_lock_backup.tmp`.
- Звіти до лабораторних робіт були розпорошені: фінальні звіти ЛР 1–4 знаходились виключно на робочому столі розробника (`C:\Users\Eclipse\Desktop\7 семестр\...`), тоді як у `deliverables/` були наявні лише ЛР 5–7 та декілька конкуруючих версій звіту ЛР 5 (`_WORKING.docx`, `_FINAL_CANDIDATE.docx`, `_фінальний.docx`).
- Локальні тестові APK-пакети для тестування (`dist/internal-testing/`) не були ізольовані через `.gitignore` на рівні папки, створюючи ризик випадкового коміту 150 МБ бінарних файлів.

### Стан ПІСЛЯ впорядкування
- **Коренева структура стала кришталево чистою, професійною та відповідає стандартам сучасного Android-проєкту:**
  ```text
  SleepTrackerSimulator/
    ├── .github/                 # Конфігурація CI/CD workflows
    ├── app/                     # Основний модуль Android (Jetpack Compose, Room v7, TFLite, Services)
    ├── virtualsleepband/        # Допоміжний модуль BLE GATT сервера (імітатор фітнес-браслета)
    ├── gradle/                  # Gradle Wrapper та Version Catalog (libs.versions.toml)
    ├── ml/                      # Python-скрипти навчання, оцінки та генерації golden-векторів
    ├── firebase-tests/          # Автоматизовані тести правил безпеки Realtime Database (11 тестів)
    ├── tools/                   # Утиліти керування емулятором Firebase та генератори звітів
    │   ├── tests/               # Тести Python-утиліт
    │   └── report-generators/   # Ізольовані скрипти генерації академічних звітів ЛР2, ЛР5, ЛР6, ЛР7
    ├── docs/                    # Повна інженерна та академічна документація
    │   ├── acceptance/          # 11 детальних звітів аудиту + 25 контрольних скриншотів UI
    │   ├── diagrams/            # Архітектурні діаграми Mermaid (ЛР 1–7)
    │   ├── ml/                  # Картка моделі (Model Card) та звіт бенчмаркінгу
    │   ├── performance/         # Профілювання рендерингу Compose (Jank analysis)
    │   ├── product-overhaul/    # Архітектурний реєстр та усунення технічного боргу
    │   └── archive/             # Історичні документи планування та аудиту
    ├── deliverables/            # Повний комплект затверджених звітів ЛР 1–7 та презентація
    │   ├── evidence/            # Контрольні скриншоти та діаграми для звітів
    │   └── archive/             # Робочі чернетки та проміжні кандидати (історія збережена)
    ├── dist/                    # Локальні збірки APK та інструкції для тестувальника (в .gitignore)
    ├── build.gradle.kts         # Кореневий build-скрипт Gradle
    ├── settings.gradle.kts      # Конфігурація модулів (:app, :virtualsleepband)
    ├── gradle.properties        # Налаштування JVM та кешів Gradle
    ├── gradlew / gradlew.bat    # Gradle wrapper скрипти
    ├── firebase.json            # Конфігурація емуляторів Firebase
    ├── database.rules.json      # Захищені правила безпеки Realtime Database
    ├── README.md                # Головна візитівка проєкту зі швидкими посиланнями на звіти
    └── .gitignore               # Вичерпний та вивірений файл виключень Git
  ```
- Звільнено понад **930 МБ дискового простору**, видалено все сміття від агентів, а корисні технічні результати та академічні звіти консолідовано в єдиний реєстр.

---

## 2. Перелік та обґрунтування видалених файлів (Removed Files)

Усі видалені файли належать виключно до категорії **SAFE TO CLEAN**. Жодного рядка робочого коду Android, тестів чи унікальних даних видалено не було:

1. **Службові каталоги Codex-агентів (931 МБ, 18 742 файли):**
   - `.codex-pptx/node_modules/` (356.58 МБ) та `.codex-artifacts/pptx/node_modules/` (356.58 МБ) — тимчасові залежності NPM (`@oai/artifact-tool`, `sharp`, `canvas`, `libvips`), завантажені агентом для компіляції слайдів. Фінальний результат збережено у `deliverables/ЛР-7_Презентація_SleepTrackerSimulator.pptx` та `.pdf`.
   - `.codex-doc/` (174.64 МБ, 1422 файли) — растрові зображення сторінок звітів (`lr*-render/*.png`), тимчасові знімки вікон емулятора та проміжні PDF-експорти.
   - `.codex-docx-qa/` (33.58 МБ, 135 файлів) — растрові рендери сторінок Word для перевірки верстки.
   - `.codex-artifacts/` (залишкові XML-дампи `today.xml`, `history.xml` тощо) — знімки ієрархії UI-елементів емулятора.
2. **Тимчасові та службові файли в `tools/` (36 файлів, ~5 МБ):**
   - Каталоги `tools/qa_*` (11 папок) та файли `tools/qa_*.pdf` (8 файлів) — проміжні візуальні рендери звітів LibreOffice/Word під час налагодження формул OMML 29.09.2026.
   - `tools/doc0.json`, `doc1.json`, `doc2.json`, `final_doc.json`, `inspect_lr1.json`, `narrative_final.json`, `sections_final.json` — проміжні дампи парсингу параграфів docx.
   - `tools/screen_*.png`, `tools/emulator_current.png`, `tools/logcat_screen.png`, `tools/device_found_cropped.png` — робочі скриншоти 29.09.2026, замінені актуальними скриншотами в `docs/acceptance/screenshots/`.
   - `tools/word_lock_backup.tmp` — прихований тимчасовий lock-файл Microsoft Word (162 байти).
3. **Кореневий лог-файл:**
   - `database-debug.log` — лог локального запуску емулятора Firebase RTDB (3.7 КБ), автоматично генерується при повторному запуску тестів.

---

## 3. Переміщені та впорядковані файли (Moved / Reorganized Files)

| Вихідний шлях | Новий шлях | Причина та технічне обґрунтування |
|---|---|---|
| `PLAN.md` | `docs/archive/PLAN_INITIAL_2026-09-28.md` | Історичний початковий план ЛР 1–7, складений до старту імплементації. Прибрано з кореня для чистоти структури, збережено для історії. |
| `LAB2_HANDOFF.md` | `docs/archive/LAB2_HANDOFF_2026-09-29.md` | Специфікація передачі контексту для ЛР 2 від 29.09.2026. Переміщено в архів документації. |
| `docs/PROJECT_CLEANUP_AUDIT.md` | `docs/archive/PROJECT_CLEANUP_AUDIT_2026-10-20.md` | Попередній проміжний аудит репозиторію після завершення ЛР 7 (20.10.2026). Перенесено в архів через заміну поточним фінальним звітом. |
| `deliverables/ЛР-5_..._WORKING.docx` | `deliverables/archive/ЛР-5_..._WORKING_v1.docx` | Рання робоча чернетка звіту ЛР 5 (351 КБ), додана в коміті `9f2a032`. Переміщена через `git mv` для збереження Git-історії. |
| `deliverables/ЛР-5_..._FINAL_CANDIDATE.docx` та `.pdf` | `deliverables/archive/ЛР-5_..._FINAL_CANDIDATE.docx` та `.pdf` | Проміжний кандидат звіту ЛР 5 від 13.10.2026. Архівовано, щоб уникнути плутанини з фінальним варіантом. |
| `deliverables/ЛР-5_..._фінальний.docx` | `deliverables/ЛР-5_ПМСС_Гончаров_Вадим_ОІ-43.docx` | Нормалізація імені файлу до єдиного академічного стандарту (ідентичний до файлу на Desktop, хеш `c8e7498d5aea`). |
| `deliverables/ЛР-5_..._фінальний.pdf` | `deliverables/ЛР-5_ПМСС_Гончаров_Вадим_ОІ-43.pdf` | Нормалізація імені підсумкового PDF-документа ЛР 5. |
| `tools/generate_lab*.py`, `tools/audit_*.py`, `tools/create_diagram.py`, `tools/ble_state_machine.png` тощо (17 файлів) | `tools/report-generators/` | Ізоляція скриптів компіляції Word/PPTX звітів у власну підпапку з власним `README.md`. Корінь `tools/` звільнено під активні інструменти розробки. |

---

## 4. Збережені критичні матеріали (Preserved Files)

1. **Android Source Code & Tests:**
   - Модуль `:app` (100% Kotlin коду, Jetpack Compose UI, Navigation, Room v7 DAOs, `SessionCoordinator`, `RecordingService`, `SlidingWindowSegmenter`, `SmartRuleEngine`, `ReplayEngine`).
   - Модуль `:virtualsleepband` (автономний BLE GATT сервер з підтримкою сервісу пульсу `0x180D`).
   - Усі 197 юніт-тестів та інструментальних тестів.
2. **Room Schemas & Migrations:**
   - Файли `app/schemas/.../1.json` – `7.json` збережено в Git без змін. Усі міграції v1→v7 верифіковані.
3. **Machine Learning & TFLite Model:**
   - Бінарна модель `app/src/main/assets/activity_classifier.tflite` (11.1 КБ).
   - `app/src/main/assets/activity_labels.txt` та `ml_golden_vectors.json`.
   - Повний pipeline навчання та валідації в `ml/` (`train_activity_model.py`, `evaluate_models.py`, `generate_golden_vectors.py`, `requirements.txt`).
   - Локальне віртуальне середовище `ml/.venv` із TensorFlow та Scikit-Learn збережено на диску та надійно заігноровано.
4. **Хмарна інтеграція та правила безпеки:**
   - `database.rules.json` (детальні валідаційні правила Firebase RTDB).
   - `firebase.json` (налаштування емуляторів).
   - `firebase-tests/database.rules.test.mjs` (11 автоматизованих тестів, 100% PASS).
5. **Тестові APK для друга (`dist/internal-testing/`):**
   - `SleepSense-Internal-Test.apk` (SHA-256: `BA42206153DCC0B97BDD4ECCAF7E03A667B2BBC6879623FC827F5DC69D2A6216` — верифіковано!).
   - `VirtualSleepBand-Internal-Test.apk` (SHA-256: `7BACEB3FA098B27F38BD039BBC55234066D8AD2561124F8C0511A0CA7B231610` — верифіковано!).
   - `SleepSense-2.0-Friend-Test-Package.zip`.
   - Усі супровідні документи українською мовою: `INSTALL_AND_TEST_UK.md`, `FRIEND_TEST_PLAN_UK.md`, `BUG_REPORT_TEMPLATE_UK.md`, `KNOWN_LIMITATIONS.md`, `SHA256SUMS.txt`.
6. **Академічні матеріали користувача (ЛР 1–7):**
   - Усі затверджені звіти від ЛР 1 до ЛР 7 зібрані в `deliverables/`:
     - `ЛР-1_МПСС_Гончаров_Вадим_ОІ-43.docx`
     - `ЛР-2_МПСС_Гончаров_Вадим_ОІ-43.docx`
     - `ЛР-3_ПМСС_Гончаров_Вадим_ОІ-43.docx`
     - `ЛР-4_ПМСС_Гончаров_Вадим_ОІ-43.docx`
     - `ЛР-5_ПМСС_Гончаров_Вадим_ОІ-43.docx` та `.pdf`
     - `ЛР-6_ПМСС_Гончаров_Вадим_ОІ-43.docx`
     - `ЛР-7_ПМСС_Гончаров_Вадим_ОІ-43.docx` та `.pdf`
     - `ЛР-7_Презентація_SleepTrackerSimulator.pptx` та `.pdf`

---

## 5. Гігієна Git (Git Hygiene & Status)

### Що налаштовано в `.gitignore`:
- Стандартні каталоги збірки Android/Gradle: `.gradle/`, `.kotlin/`, `.idea/`, `build/`, `captures/`, `.cxx/`.
- Файли локального середовища: `local.properties`.
- Локальні віртуальні середовища Python: `ml/.venv/`, `tools/.venv/`, `__pycache__/`, `*.py[cod]`.
- Секрети та ключі підпису: `*.jks`, `*.keystore`, `keystore.properties`, `google-services.json`, `*credentials*.json`.
- Папка тестування `dist/` (дозволяє тримати готові APK на диску без внесення 150 МБ у репозиторій).
- Резервні шаблони ігнорування службових папок агентів: `.codex-doc/`, `.codex-docx-qa/`, `.codex-pptx/`, `.codex-artifacts/`.

### Стан Git Working Tree:
- **Жодного перезапису історії (No rebase / No force-push).**
- **Жодного автоматичного мерджу в `main` чи сторонніх дій.**
- Усі переміщення tracked-файлів зафіксовано засобами `git mv`.
- Відсутні сліпі операції `git add .`.

---

## 6. Локальні артефакти, що не потрапляють у Git (Remaining Local Artifacts)

- `local.properties` (містить шлях до локального Android SDK `C:\Users\Eclipse\AppData\Local\Android\Sdk`).
- `app/google-services.json` (конфігурація клієнта Firebase, захищена в `.gitignore`).
- `ml/.venv/` (робоче Python-середовище з TensorFlow, Keras та Scikit-Learn обсягом 1.7 ГБ).
- `tools/.venv/` (Python-середовище з Firebase Admin SDK).
- `dist/internal-testing/` (готові тестові APK та zip-архів для друга).
- `firebase-tests/node_modules/` (залежності тестового фреймворку Node.js).
- `.gradle/`, `build/`, `app/build/`, `virtualsleepband/build/` (локальні кеші компілятора та згенеровані APK).

---

## 7. Повторна технічна верифікація збірки та тестів (Build Verification)

Після завершення впорядкування та очищення структури репозиторію було виконано повторний технічний цикл:

| Команда перевірки | Результат | Метрики / Деталі |
|---|:---:|---|
| `.\gradlew :app:testDebugUnitTest` | **PASSED** | **197 тестів виконано, 0 помилок, 0 пропущено (100% успіх)**. Час: 10 с. |
| `.\gradlew :app:assembleDebug` | **PASSED** | Збірка успішна, згенеровано APK додатку `app-debug.apk`. Час: 13 с. |
| `.\gradlew :app:lintDebug` | **PASSED** | Статичний аналіз Android Lint завершено без критичних помилок. Час: 25 с. |
| `.\gradlew :virtualsleepband:assembleDebug` | **PASSED** | Збірка допоміжного модуля BLE-браслета успішна. Час: 24 с. |
| `git diff --check` | **PASSED** | Жодних конфліктних маркерів чи проблем із перенесенням рядків. |
| `ml/evaluate_models.py` | **PASSED** | Holdout $N=750$: Dense TFLite Accuracy 98.53%, Latency 36.5 $\mu$s. |
| `ml/generate_golden_vectors.py` | **PASSED** | 12 golden-векторів згенеровано та звірено з TFLite моделлю. |
| `npm --prefix firebase-tests test` | **PASSED** | **11 з 11 тестів безпеки Realtime Database пройдено успішно**. |

---

## 8. Готовність до академічного захисту (Academic Delivery Readiness)

Викладач, асистент або екзаменаційна комісія отримують максимально комфортний досвід аудиту:

1. **Android Source Code:**
   Чітко розділений на модулі `:app` (чиста архітектура: `domain`, `data`, `recording`, `history`, `ml`, `cloud`, `ui`) та `:virtualsleepband`.
2. **Доступ до всіх 7 лабораторних робіт:**
   - У застосунку: розділ **Settings → Developer Lab (Labs 1–7)** містить 7 незалежних вкладок інспекції сенсорів, BLE, Firebase, актуаторів, Room v7, TFLite та наскрізного трейсу.
   - Окремий перемикач **Lab View** рендерить класичний монолітний дашборд навчальної лабораторії.
3. **Університетські звіти:**
   У каталозі `deliverables/` зібрано **всі 7 звітів** (`.docx` / `.pdf`) у канонічному форматі кафедри АСУ разом зі слайдами презентації (`.pptx` / `.pdf`) та описовим індексом [`deliverables/README.md`](../deliverables/README.md).
4. **Зрозумілий README:**
   Головний [`README.md`](../README.md) позбавлений рекламного пафосу, чітко розмежовує медичні обмеження акселерометра від спортивного трекінгу, наводить чесні метрики ML та містить інтерактивні посилання на кожен звіт і тест.
5. **Машинне навчання:**
   Повна прозорість: синтетичні датасети позначені, метрики 4 моделей порівняно на holdout-вибірці, картка моделі `MODEL_CARD.md` описує межі застосування, а golden-вектори гарантують збіг прогнозів Python та Kotlin до $10^{-5}$.
6. **Запуск та відтворюваність:**
   Усі інструкції перевірені та працюють через стандартний Gradle Wrapper на JDK 21+.

---

## 9. Питання для ручного рішення користувача (Outstanding Questions)

1. **Коміт змін у поточну гілку:**  
   Усі зміни підготовлені та локально перевірені на гілці `feature/sleepsense-product-overhaul`. За потреби користувач може зафіксувати їх окремим вибірковим комітом:
   ```powershell
   git add .gitignore README.md docs/ deliverables/ tools/
   git commit -m "chore(cleanup): finalize academic repository structure and deliverables suite"
   ```
2. **Тестові APK у `dist/`:**  
   Артефакти для тестувальника (`SleepSense-Internal-Test.apk`, `VirtualSleepBand-Internal-Test.apk`, ZIP) збережені на диску та надійно заігноровані у `.gitignore`. Користувач може передати їх другу або завантажити у GitHub Releases без перевантаження дерева репозиторію.
3. **Оригінальні файли на робочому столі:**  
   Каталог `C:\Users\Eclipse\Desktop\7 семестр\Програмування мобільних смарт-систем\` залишився повністю недоторканим.

---
**Висновок:** Репозиторій SleepTrackerSimulator / SleepSense 2.0 успішно пройшов повну інженерну ревізію, очищений від 930 МБ службового сміття, на 100% верифікований автоматичними тестами та повністю готовий до університетського захисту та публікації.
