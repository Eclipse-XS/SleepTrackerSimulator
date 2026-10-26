# Repository cleanup audit

Дата перевірки: 20 жовтня 2026 року.

## 1. Before / After

Аудит виконано на `release/labs-1-7-verified`, HEAD `9a48e0e0e62c19dfa2f0870f2649af9fb8982682`. До змін tracked-дерево було чистим; локально існували untracked матеріали в `.codex-artifacts/`, `deliverables/` і `tools/`. Вони не видалялися і не перезаписувалися.

| Метрика | До | Після |
|---|---:|---:|
| Tracked files | 200 | 189 |
| Tracked size | 7,079 MiB | близько 7,02 MiB |
| Files under `docs/` | 44 | 35 |
| Top-level product directories | `app`, `ml`, `firebase-tests`, `tools`, `docs`, `deliverables`, `.github` | без змін |

Повний локальний каталог значно більший за Git-дерево через ignored caches і environments: `ml` близько 1,56 GiB, `app` близько 552 MiB, `.codex-doc` близько 175 MiB, `firebase-tests` близько 147 MiB, `tools` близько 104 MiB. Це не вміст репозиторію. `.gradle/`, `.idea/`, `.kotlin/`, `build/`, `app/build/`, `.codex-doc/`, `.codex-docx-qa/` і `.codex-pptx/` уже були ignored. Додано ignore для `.codex-artifacts/` і локальних document-QA renders у `tools/`.

## 2. Deleted

Документи нижче були проміжними знімками одного remediation-процесу, містили застарілі версії Room, старі test counts або статуси, що вже суперечили поточному коду:

- `docs/FINAL_INDEPENDENT_AUDIT_BASELINE_UK.md`
- `docs/FINAL_LABS_1_TO_7_AUDIT_UK.md`
- `docs/FINAL_LABS_6_7_ACCEPTANCE_REPORT_UK.md`
- `docs/FINAL_LABS_6_7_INDEPENDENT_SECURITY_AUDIT_UK.md`
- `docs/FINAL_LABS_6_7_REMEDIATION_REPORT_UK.md`
- `docs/GITHUB_PUBLICATION_VERIFICATION_UK.md`
- `docs/LAB7_FINAL_IMPLEMENTATION_AND_AUDIT_UK.md`
- `docs/REMEDIATION_PASS_2_REPORT_UK.md`
- `docs/REMEDIATION_REPORT_UK.md`

`docs/DEFENSE_GUIDE_LAB7_UK.md` видалено як коротший дубль актуальнішого `docs/DEFENSE_GUIDE_LABS_6_7_UK.md`. У ньому також були неправильні твердження про Room v4 і migrations лише 1→4.

Видалено два шаблонні тести Android Studio без регресійної цінності:

- `app/src/test/java/com/example/sleeptrackersimulator/ExampleUnitTest.kt` — перевіряв лише `2 + 2 = 4`.
- `app/src/androidTest/java/com/example/sleeptrackersimulator/ExampleInstrumentedTest.kt` — перевіряв лише package name.

## 3. Merged

- Defense content консолідовано навколо `docs/DEFENSE_GUIDE_LABS_6_7_UK.md`, `docs/LAB7_DEMO_SCRIPT_UK.md` і `docs/LAB7_REQUIREMENTS_MATRIX_UK.md`; окремий Lab 7 guide не містив унікального доказу.
- Історичні audit/remediation-висновки замінено цим одним звітом. Історія видалених документів лишається в Git.
- Матриця Lab 7 тепер посилається на фактичний Room v6 atomic/gated outbox і не називає performance PASS при 90,99% modern jank.

## 4. Refactored

- `README.md`: Room v5 виправлено на Room v6; описано atomic execution/outbox preparation і reconciliation; прибрано посилання на видалений remediation report.
- `docs/LAB7_REQUIREMENTS_MATRIX_UK.md`: виправлено R7-04, R7-16, R7-18 і R7-19. Performance має статус FAIL, manual/hardware coverage — PARTIAL.
- `.gitignore`: додано локальні Codex artifacts, QA renders, screenshots та inspection JSON. Файли не видалено з диска.
- Бізнес-код не змінювався. Спроби прибрати `fragment-ktx` і serialization BOM скасовано: lint та Room instrumentation довели, що вони забезпечують version resolution/runtime compatibility.

## 5. AI slop findings

Висновки ґрунтуються на технічній надмірності та суперечностях, а не на припущенні про походження тексту.

- `README.md:245` у baseline називав Room v5, тоді як `SleepTrackerDatabase.kt` має version 6 і migrations 1→6.
- `docs/DEFENSE_GUIDE_LAB7_UK.md:13-14` називав Room v4 і migrations 1→4.
- `docs/REMEDIATION_PASS_2_REPORT_UK.md:32,72` фіксував schema v3 і 149 tests як поточні факти.
- `docs/FINAL_LABS_1_TO_7_AUDIT_UK.md:80` фіксував 151 tests, хоча актуальний post-cleanup run виконав 158 JVM tests.
- `docs/FINAL_LABS_6_7_INDEPENDENT_SECURITY_AUDIT_UK.md:24` заявляв, що correlated device E2E та instrumentation не підтверджені; у репозиторії вже є executable correlated test і committed evidence.
- `docs/LAB7_REQUIREMENTS_MATRIX_UK.md` одночасно ставив profiling PASS і посилався на невдалий performance result. Статус виправлено на FAIL.
- Дев'ять audit/remediation-файлів повторювали одні й ті самі компоненти, але описували різні історичні стани як актуальні.
- Два template tests збільшували test count без перевірки поведінки системи.

Статичний пошук не підтвердив безпечного видалення production Kotlin classes. Низькі reference counts для Room entities, Worker, Compose state і Android entry points не є доказом dead code через reflection, framework wiring та внутрішнє використання в межах файлу.

## 6. Preserved

Збережено:

- SensorManager pipeline і lifecycle-controlled accelerometer listener;
- `VirtualSleepBand`, Mock BLE state machine та correlated alert acknowledgement;
- Firebase Auth/RTDB repositories, rules і emulator tests;
- actuator control, typed results і remote control;
- Room schemas 1–6, migrations, DAO tests, history та durable outbox;
- analytics і Vico chart;
- TFLite model, labels, reproducible training script і evaluation JSON;
- Smart Scenarios, rule engine, event coordinator, WorkManager recovery та idempotency;
- correlated E2E test, profiling raw evidence, screenshots, video і Mermaid sources;
- final tracked Lab 6/7 DOCX/PDF/PPTX deliverables and Lab 5 evidence.

## 7. Tests

Статуси означають лише фактично виконані команди цього аудиту.

| Перевірка | До очищення | Після очищення |
|---|---|---|
| `testDebugUnitTest` | PASS; baseline task completed, exact fresh count not separately captured | PASS: 158, failures 0, errors 0, skipped 0 |
| `assembleDebug` | PASS | PASS |
| `assembleRelease` | PASS; unsigned lab build | PASS; unsigned lab build |
| `lintDebug` | PASS | PASS |
| `compileDebugAndroidTestSources` | PASS | PASS |
| Firebase Rules emulator | PASS: 8/8 | PASS: 8/8 |
| `connectedDebugAndroidTest` | NOT RUN before edits in this audit | PASS: 17/17 on Pixel_10_Pro AVD, Android 16, with Auth/Database emulators |
| `git diff --check` | not applicable | PASS; only Git line-ending notices |

Під час cleanup два проміжні regression runs були корисними й не приховані:

1. Без `fragment-ktx` `lintVitalRelease` повернув `InvalidFragmentVersionForActivityResult`; dependency відновлено.
2. Без serialization BOM Room migration instrumentation повернув `AbstractMethodError` у `FieldBundle$$serializer`; BOM відновлено. Повторний suite пройшов 17/17.

GitHub стан на момент аудиту:

- `origin/main`: `22f1632efcae9d31b44be34f60bc56ab9dcddb7a`.
- `origin/release/labs-1-7-verified`: `9a48e0e0e62c19dfa2f0870f2649af9fb8982682`.
- Гілки розійшлися: main має 1 власний commit, release — 12 власних commits.
- PR #1 `Finalize and independently verify Labs 6–7`: OPEN, MERGEABLE; Android і Firebase Rules checks на HEAD завершилися SUCCESS.

## 8. Remaining issues

- Debug AVD performance acceptance лишається FAIL: 90,99% modern jank. Потрібні segmented Perfetto trace і physical-device release benchmark.
- Відео демонструє Smart Scenario creation/persistence, але не повний desktop Sensor → ML → Room → Firebase → Actuator → BLE lifecycle.
- ML accuracy 97,22% стосується лише synthetic test set; медична або польова валідність не доведена.
- Release build не має production signing, obfuscation, Play review, App Check або user-facing export/delete.
- Kotlin compiler повідомляє nullable-receiver warning у `AtomicJsonCloudStateStoreTest.kt:41`; тест проходить, production code не зачеплено.
- Untracked Lab 5 final candidates, report generators, diagrams and inspection scripts are owner material. Вони мають статус REVIEW і свідомо не видалялися та не додавалися до Git.
- Tracked `deliverables/ЛР-5_ПМСС_Гончаров_Вадим_ОІ-43_WORKING.docx` не видалено: потенційні replacement-файли лишаються untracked, тому автоматична заміна була б небезпечною.

## 9. Final Git status

Tracked зміни: `.gitignore`, `README.md`, `docs/LAB7_REQUIREMENTS_MATRIX_UK.md`, цей звіт, видалення десяти застарілих Markdown-документів і двох template tests. Production Kotlin, schemas, model, rules, evidence і deliverables не змінено.

Untracked користувацькі матеріали в `deliverables/` і `tools/` залишено без змін. Коміти, push, merge, reset і clean не виконувалися.
