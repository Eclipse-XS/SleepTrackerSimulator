# ML evidence

- Asset: `app/src/main/assets/activity_classifier.tflite`.
- SHA-256: `1CF844F774355E54F820C4860304DC43E42A4015F57C7097CEE9D16227858311`.
- Size: 11,368 bytes.
- Contract: float32 `[1,20,3]` → float32 `[1,3]`.
- Labels: REST, RESTLESS, ACTIVE; 20 XYZ accelerometer samples per window.
- Runtime: TensorFlow Lite 2.16.1, on-device inference.

Повторна команда `ml\.venv\Scripts\python.exe ml\evaluate_models.py` використала Train=3600, Test=900, Holdout=750. На synthetic holdout: production Dense TFLite accuracy/macro-F1 `0.9800/0.9800`, latency `7.1 µs`, size `11.1 KB`; 1D CNN `1.0000/1.0000`, `13.4 µs`, `14.1 KB`. Ці числа не є точністю на людях і не є Android latency.

AndroidJUnitRunner: 4 ML instrumentation tests PASS, включно з усіма 12 golden vectors і Python/TFLite parity. Модель класифікує рух телефона, а не стадії сну, апное чи клінічний стан.
