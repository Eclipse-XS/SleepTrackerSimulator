# Контрольне профілювання 20.10.2026

## Середовище

- AVD: `Pixel_10_Pro`, Android API 36.
- Build: debug.
- Екран: Dashboard.
- Тривалість контрольованого сегмента: 15 секунд після `dumpsys gfxinfo reset`.
- Інструменти: `adb shell dumpsys gfxinfo`, `adb shell dumpsys meminfo`.

## Результат

| Метрика | Значення |
|---|---:|
| Frames | 333 |
| Modern janky frames | 303 / 90,99% |
| Legacy jank | 59 / 17,72% |
| p50 | 18 ms |
| p90 | 34 ms |
| p95 | 34 ms |
| p99 | 57 ms |
| Missed vsync | 2 |
| Slow UI thread | 215 |
| TOTAL PSS | 133251 KB |
| TOTAL RSS | 269060 KB |
| Swap PSS | 79 KB |

## Вердикт

`FAIL` за modern frame metric. Результат кращий за попередній debug AVD run 92,02% jank і p95 близько 101 ms лише за latency percentile; частка modern jank залишається неприйнятною. Це короткий debug/emulator segment, а не release benchmark. Шість окремих режимів навантаження, Perfetto trace і physical-device release run не виконані. Production readiness за цим виміром не підтверджена.
