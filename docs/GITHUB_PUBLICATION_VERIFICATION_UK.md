# Перевірка GitHub-публікації

- Початкова локальна гілка: `fix/pre-lab6-reliability`.
- Original HEAD: `f1fb5805cefbebaa39fb87cc4ff357a91459ffb5`.
- Verification branch: `release/labs-1-7-verified`.
- Implementation commit: `021e884`.
- Report-containing commit: визначається як HEAD цієї гілки; remote SHA перевірено після push командою `git ls-remote`.
- Remote URL: `https://github.com/Eclipse-XS/SleepTrackerSimulator/tree/release/labs-1-7-verified`.

## Опубліковано

Android source, executable TFLite asset, labels, Room schemas 2–4, migrations, outbox/WorkManager, Smart Rule Engine/UI, Firebase Rules/tests, ML training script та evaluation metadata, JVM/instrumentation tests, diagrams, reports, screenshots, profiling evidence, video і PPTX.

## Перевірки

- `testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `lintDebug`, `compileDebugAndroidTestSources`, `connectedDebugAndroidTest`: BUILD SUCCESSFUL.
- Firebase Emulator Suite: 4 tests, 0 failures.
- Secrets scan: збігів private keys, service-account secrets, OAuth secrets або tokens не знайдено.
- `git diff --cached --check`: clean перед implementation commit.
- TFLite SHA-256: `1CF844F774355E54F820C4860304DC43E42A4015F57C7097CEE9D16227858311`.

## Не опубліковано

Локальні QA/temp каталоги `.codex-*`, `database-debug.log`, Firebase client config `google-services.json`, Gradle/build caches, `node_modules`, локальні keystores та unrelated Lab 5 working artifacts.

## Відкриті питання

Smart Rule execution не має окремого durable Firebase event payload. Full correlated desktop E2E video відсутнє. Performance evidence стосується debug AVD, не фізичного release device.
