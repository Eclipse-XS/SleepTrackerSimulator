# Performance evidence

Single debug AVD snapshot after launch; not a release benchmark:

- Pixel_10_Pro AVD, API 36, 1280×2856 @ 480 dpi.
- Total PSS: 134,444 KB; RSS: 282,252 KB; swap 0.
- Room DB file reported by `dumpsys meminfo`: 204 KB.
- `dumpsys gfxinfo`: 81 frames, 44 modern janky frames (54.32%), p50 36 ms, p90 250 ms, p95 650 ms.
- Debug APK: 88,610,709 bytes.
- Python host latency for production Dense TFLite: 7.1 µs. This is not Android/device latency.

The old 90.99% figure must not be reused as current product performance. The new snapshot is also too short and contaminated by debug/emulator startup to support a product performance claim. Release-like Macrobenchmark, long recording memory, database growth and battery drain remain NOT VERIFIED.
