# RecordingService evidence

`RecordingService` є Android foreground service з health service type. Старт очікує створення сесії до реєстрації джерела. Pause/Resume відновлює лише фактичний SourceMode і sampling rate. MainActivity перевіряє `ACTIVITY_RECOGNITION` перед стартом.

Runtime на Pixel_10_Pro AVD API 36:

1. Today idle — READY TO RECORD.
2. Start — RECORDING IN PROGRESS, PHONE, 20 Hz, on-device TFLite.
3. Pause — paused state.
4. Resume і Finish — completed summary.
5. History/Session Details — завершена PHONE сесія, coverage 100%.

Process restart test підтверджує `INTERRUPTED` замість хибної безперервності та безпечне завершення збереженої сесії.
