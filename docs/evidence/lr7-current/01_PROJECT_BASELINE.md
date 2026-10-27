# Project baseline

Перевірено 27.10.2026 у Windows 11, timezone Europe/Kyiv.

- Branch: `feature/sleepsense-product-overhaul`.
- HEAD: `78ba61213bbada19f69147572328d0eb2ea8263a`.
- Baseline branch: `release/labs-1-7-verified` at `9a48e0e`.
- Working tree: dirty. SHA не є повним ідентифікатором перевіреної реалізації.
- Modified tracked files на початку: `.gitignore`, `README.md`, `docs/ml/EVALUATION_REPORT.md`, `docs/ml/MODEL_CARD.md`, `docs/ml/model_comparison.json`; також staged renames архівних документів і untracked deliverables/tools.
- `git diff --check`: PASS; лише попередження LF→CRLF.
- Remote: `origin=https://github.com/Eclipse-XS/SleepTrackerSimulator.git`; push не виконувався.

## Toolchain

- Gradle 9.6.0; Kotlin 2.3.21; AGP 9.4.1.
- Launcher JDK 11.0.30; Gradle daemon toolchain Java 25.
- Android modules: `:app`, `:virtualsleepband`.
- Main app: applicationId `com.example.sleeptrackersimulator`, version `1.0`/1, minSdk 31, target/compileSdk 37.
- Companion: applicationId `com.example.virtualsleepband`, version `1.0`/1, minSdk 26, target/compileSdk 37.
- Room 2.8.5, WorkManager 2.12.0, Firebase BoM 34.19.0, TensorFlow Lite 2.16.1, Compose BoM 2026.09.00.

Повний `git status` не дублюється тут через велику кількість користувацьких документів; він був отриманий командою `git status --short --untracked-files=all` перед тестами.
