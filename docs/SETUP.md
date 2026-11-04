# Setup and verification

## Build

Use Android Studio with Android SDK and Java 21 (CI's Java version). The Gradle wrapper defines the exact Gradle version.

Firebase's Google Services plugin expects `app/google-services.json`. Do not commit credentials: configure a development Firebase project or reproduce the **non-production dummy configuration** from `.github/workflows/android-ci.yml` for build-only testing. That dummy configuration does not enable live Firebase access.

```bash
./gradlew :app:testDebugUnitTest :virtualsleepband:testDebugUnitTest
./gradlew :app:assembleDebug :virtualsleepband:assembleDebug
```

Build outputs: `app/build/outputs/apk/debug/app-debug.apk` and `virtualsleepband/build/outputs/apk/debug/virtualsleepband-debug.apk`.

## Python

```bash
python -m pip install -e ".[dev]"
python -m pip check
ruff check ml pipelines tests --select=E9,F63,F7,F82
python -m pytest -q tests
python -m pipelines.cli.main generate --profile SMALL --seed 42
python -m pipelines.cli.main validate
```

Sample datasets may be generated locally and are not all tracked. For exact CI coverage see `.github/workflows/python-datascience-ci.yml`.

## VirtualSleepBand

Install both APKs. Start the VirtualSleepBand server and select `VIRTUAL_WEBSOCKET` in SleepSense Devices. For both apps on the **same emulator**, use `ws://127.0.0.1:8765`; for two emulators, consult [virtual wearable networking](VIRTUAL_WEARABLE.md). A websocket test does **not** validate the Bluetooth RF stack.

## Evidence limits

CI runs and physical-device checks are separate. Do not infer that physical BLE or live Firebase has passed from an APK build, unit test or local simulated WebSocket session. See [TESTING](TESTING.md).
