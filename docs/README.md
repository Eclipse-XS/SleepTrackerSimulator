# SleepSense 2.0 — Документація проєкту та технічний індекс

Цей каталог містить структуровану інженерну, академічну та дослідницьку документацію проєкту **SleepSense 2.0** (SleepTrackerSimulator).

---

## 1. Академічні матеріали та підготовка до захисту (ЛР 1–7)
- [**DEFENSE_GUIDE_LABS_6_7_UK.md**](DEFENSE_GUIDE_LABS_6_7_UK.md) — Докладний посібник для захисту лабораторних робіт № 6 (TFLite) та № 7 (комплексний мініпроєкт).
- [**LAB7_DEMO_SCRIPT_UK.md**](LAB7_DEMO_SCRIPT_UK.md) — Сценарій демонстрації комплексної роботи системи викладачеві під час захисту.
- [**LAB7_REQUIREMENTS_MATRIX_UK.md**](LAB7_REQUIREMENTS_MATRIX_UK.md) — Матриця відповідності навчальним вимогам кафедри АСУ для лабораторної роботи № 7.
- [**architecture_labs_1_7.mmd**](architecture_labs_1_7.mmd) — Наскрізна Mermaid-діаграма взаємодії компонентів усіх семи лабораторних робіт.
- [**e2e_sequence_labs_7.mmd**](e2e_sequence_labs_7.mmd) — Послідовність проходження сигналу від сенсорів до актуаторів та хмари.
- **[Каталог deliverables/](../deliverables/README.md)** — Готові університетські звіти у форматі `.docx`/`.pdf` та слайди презентації.

---

## 2. Незалежні аудити приймального тестування (Acceptance Suite)
Каталог [`acceptance/`](acceptance/) містить звіти глибокого технічного аудиту системи:
- [`01_REPOSITORY_AND_RUNTIME_AUDIT.md`](acceptance/01_REPOSITORY_AND_RUNTIME_AUDIT.md) — Ревізія структури репозиторію та запуск у середовищі.
- [`02_ANDROID_TEST_RESULTS.md`](acceptance/02_ANDROID_TEST_RESULTS.md) — Результати виконання 197 юніт-тестів та інструментальних тестів.
- [`03_ROOM_MIGRATION_VERIFICATION.md`](acceptance/03_ROOM_MIGRATION_VERIFICATION.md) — Верифікація ланцюжка міграцій Room v1 → v7 без втрати даних.
- [`04_ML_RESULTS_REVALIDATION.md`](acceptance/04_ML_RESULTS_REVALIDATION.md) — Порівняльний аналіз точності та затримки моделей ML.
- [`05_BLE_RUNTIME_VERIFICATION.md`](acceptance/05_BLE_RUNTIME_VERIFICATION.md) — Верифікація протоколу GATT, підписки CCCD та емулятора браслета.
- [`06_FIREBASE_SYNC_E2E.md`](acceptance/06_FIREBASE_SYNC_E2E.md) — Хмарна синхронізація через транзакційний outbox та 11 тестів безпеки RTDB.
- [`07_UI_VISUAL_AUDIT.md`](acceptance/07_UI_VISUAL_AUDIT.md) — Візуальний аудит екранів Jetpack Compose та Developer Lab.
- [`08_PERFORMANCE_AND_APK_ANALYSIS.md`](acceptance/08_PERFORMANCE_AND_APK_ANALYSIS.md) — Профілювання пам'яті, CPU, кадрів (jank) та розміру APK.
- [`09_LABS_1_TO_7_REGRESSION.md`](acceptance/09_LABS_1_TO_7_REGRESSION.md) — Матриця нерегресії для кожної з семи лабораторних робіт.
- [`FINAL_ACCEPTANCE_VERDICT.md`](acceptance/FINAL_ACCEPTANCE_VERDICT.md) — Підсумковий висновок про готовність системи до захисту.
- [`FINAL_PRODUCT_HARDENING_REPORT.md`](acceptance/FINAL_PRODUCT_HARDENING_REPORT.md) — Звіт про стабілізацію продуктової архітектури.
- **[`screenshots/`](acceptance/screenshots/)** — 25 верифікованих скриншотів інтерфейсу та всіх вкладок інспектора Developer Lab.

---

## 3. Машинне навчання та моделі (ML 2.0)
Каталог [`ml/`](ml/) документує розробку та оцінку TFLite класифікатора:
- [`MODEL_CARD.md`](ml/MODEL_CARD.md) — Офіційна картка моделі `ActivityClassifier` (архітектура, вхідні розмірності, метрики, обмеження).
- [`EVALUATION_REPORT.md`](ml/EVALUATION_REPORT.md) — Звіт порівняльного бенчмаркінгу 4 моделей (Rule-Based, Random Forest, Dense TFLite, 1D CNN).
- [`model_comparison.json`](ml/model_comparison.json) — Машиночитані метрики Holdout-вибірки ($N=750$).

---

## 4. Архітектурні рішення (Architecture & Overhaul)
Каталог [`product-overhaul/`](product-overhaul/) описує еволюцію архітектури від навчальних лабораторних до єдиного застосунку:
- [`01_CURRENT_ARCHITECTURE.md`](product-overhaul/01_CURRENT_ARCHITECTURE.md) — Початковий стан компонентів.
- [`02_TECH_DEBT_REGISTER.md`](product-overhaul/02_TECH_DEBT_REGISTER.md) — Реєстр усуненого технічного боргу.
- [`03_LAB_COMPATIBILITY_MATRIX.md`](product-overhaul/03_LAB_COMPATIBILITY_MATRIX.md) — Сумісність функціоналу лабораторних робіт.
- [`04_TARGET_ARCHITECTURE.md`](product-overhaul/04_TARGET_ARCHITECTURE.md) — Цільова чиста архітектура SleepSense 2.0.
- [`diagrams/lab7/`](diagrams/lab7/) — 8 детальних схем архітектури, правил, датчиків та синхронізації.

---

## 5. Профілювання та продуктивність
- [`performance/FINAL_FRAME_JANK_ANALYSIS_UK.md`](performance/FINAL_FRAME_JANK_ANALYSIS_UK.md) — Аналіз плавності Compose UI (60/120 fps) за даними `gfxinfo`.
- [`evidence/`](evidence/) — Логи вимірювання пам'яті (`dumpsys meminfo`), процесора (`cpuinfo`) та відеозапис інтеграційного сценарію.

---

## 6. Архів інженерних документів
Каталог [`archive/`](archive/) містить історичні документи еволюції розробки:
- [`PLAN_INITIAL_2026-09-28.md`](archive/PLAN_INITIAL_2026-09-28.md) — Початковий технічний план реалізації ЛР1–ЛР7.
- [`LAB2_HANDOFF_2026-09-29.md`](archive/LAB2_HANDOFF_2026-09-29.md) — Специфікація передачі контексту під час розробки ЛР 2.
- [`PROJECT_CLEANUP_AUDIT_2026-10-20.md`](archive/PROJECT_CLEANUP_AUDIT_2026-10-20.md) — Попередній аудит структури після завершення ЛР 7.
- [**`PROJECT_STRUCTURE_CLEANUP_REPORT.md`**](PROJECT_STRUCTURE_CLEANUP_REPORT.md) — Фінальний інженерний звіт про впорядкування структури проєкту перед університетською здачею.
