# Device inventory

Execution date: 2026-10-28 (Europe/Kyiv)

| Target | Serial | Model | Android | API | Type | Status |
|---|---|---|---:|---:|---|---|
| Pixel_10_Pro AVD | `emulator-5554` | `sdk_gphone64_x86_64` | 16 | 36 | Emulator (`ro.kernel.qemu=1`) | AVAILABLE |
| Physical Phone A | — | — | — | — | Physical | NOT_CONNECTED |
| Physical Phone B | — | — | — | — | Physical | NOT_CONNECTED |

- `ANDROID_HOME` and `ANDROID_SDK_ROOT` were unset.
- ADB was not in `PATH`; the executable used was `C:\Users\Eclipse\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Available AVD: `Pixel_10_Pro`.
- `adb devices -l` initially returned no devices. The AVD was then started and explicitly identified as an emulator.
- No authorized USB or wireless physical-device transport was available.

The exact physical-device failures therefore cannot be classified PASS. Emulator results are reported separately.
