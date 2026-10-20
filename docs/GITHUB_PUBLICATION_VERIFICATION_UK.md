# Перевірка GitHub публікації Labs 6 і 7

## Ідентифікація

- Verification branch: `release/labs-1-7-verified`.
- Original remediation HEAD: `c779be381ccd812e8c79ce246d32ebc281709caf`.
- Pull request: `https://github.com/Eclipse-XS/SleepTrackerSimulator/pull/1`.
- Base branch: `main`.

## Локальні перевірки фінального diff

- JVM: 161 tests, 0 failures, 0 skipped.
- `assembleDebug`: PASS.
- `assembleRelease`: PASS.
- `lintDebug`: PASS із warnings про доступні dependency updates та TFLite 2.16.1 16 KB alignment.
- `compileDebugAndroidTestSources`: PASS.
- `connectedDebugAndroidTest`: BLOCKED, підключений device відсутній.
- Firebase Realtime Database Emulator: 6 tests, 0 failures.
- TFLite SHA-256: `1CF844F774355E54F820C4860304DC43E42A4015F57C7097CEE9D16227858311`.
- Secret-pattern scan: private keys і access tokens не знайдені.

## CI

`.github/workflows/android-ci.yml` запускає Android unit tests, debug build, lint і окремий Firebase Emulator job без production credentials. Android job створює непублічний dummy `google-services.json` лише в runner workspace, тому build не залежить від локального live client config. Instrumentation лишається manual gate, бо workflow не створює Android emulator.

GitHub CI можна вважати PASS лише після завершення checks для опублікованого commit. Локальна наявність workflow не є доказом remote success.

## Межі публікації

До commit включаються лише source, Room schema 5, Firebase Rules/tests, CI та Markdown reports. Незв’язані Lab 5 deliverables, локальні QA tools, build outputs, `google-services.json`, keystores і `local.properties` не включаються.

`main` не змінюється, PR не merge-иться, force-push не використовується.
