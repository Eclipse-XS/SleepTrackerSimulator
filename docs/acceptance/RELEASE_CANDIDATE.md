# SleepSense 2.0 RC1

## Package

| File | Bytes | SHA-256 |
|---|---:|---|
| `SleepSense-2.0-RC-debug.apk` | 89,157,476 | `109A630D00C6D602DAED114DD57182C96D60FB443B8A0FE28DFE8E708EAF9667` |
| `VirtualSleepBand-RC-debug.apk` | 18,502,423 | `16C6B1D999930715D05E8C79B9784BB4F54D9315131B49E966FC193FB2A1F360` |

Both APKs are debug-signed RC artifacts built from the same accepted source tree. Version: `2.0-RC1`; version code: `2000001`.

## Verified gates

- 228 JVM tests, 32 Android instrumentation/Compose tests, 11 Firebase Emulator rules tests and 30 Python tests passed.
- Dataset CSV schema, pandas ingestion and educational synthetic benchmark passed.
- Both applications built in debug and release variants; debug and release lint gates passed.
- Both RC APKs installed on Pixel_10_Pro AVD API 36. Package IDs and launcher activities resolved correctly.
- 30 min 10 sec recording/lifecycle endurance completed without process death or crash-buffer entry.

## Installation

1. Enable installation from the selected file manager or use `adb install -r <apk>`.
2. Install `SleepSense-2.0-RC-debug.apk` on Phone A.
3. Install `VirtualSleepBand-RC-debug.apk` on Phone B for the real two-phone BLE demonstration.
4. Grant runtime permissions only when the relevant lab flow requests them.

## Build configuration and limitations

- GitHub Actions uses a non-production demo `google-services.json`; its APK is not proof of real Firebase connectivity.
- A local authorized build may use the ignored `app/google-services.json`. No Firebase secret or service-account credential is committed.
- Emulator verification proves Android API calls, requested states and TorchCallback observations. It does not prove visible LED output or tactile vibration.
- Real GATT radio exchange, CCCD notifications and reconnect across two physical phones remain hardware-only acceptance items.
- Debug signing is suitable for laboratory filming, not Google Play release.
