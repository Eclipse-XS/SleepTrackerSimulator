# Аналіз frame jank

## Метод

Початковий замір виконано `adb shell dumpsys gfxinfo` на Pixel_10_Pro AVD, Android 16, debug APK, 60 секунд. Паралельно працювали sensor callbacks, Compose Dashboard і TFLite. Метрика належить frames процесу застосунку, але містить startup/warm-up та debug overhead.

## Результат

- 1028 rendered frames.
- 946 janky frames за сучасною метрикою, 92.02%.
- Legacy jank: 48.35%.
- p50 20 ms, p90 65 ms, p95 101 ms, p99 250 ms.
- PSS: 115111 KB після старту, 130093 KB на 30 с, 129377 KB на 60 с.

## Висновок

Результат відтворює проблемний debug AVD run, але не ізолює workload і не доводить аналогічну поведінку release build на фізичному пристрої. Виправлення sensor ownership усуває UI StateFlow як ML sampling source, але повторний segmented Perfetto/FrameMetrics benchmark не виконано. Production performance лишається непідтвердженою.
