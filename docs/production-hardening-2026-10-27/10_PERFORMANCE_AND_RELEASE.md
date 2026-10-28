# Performance and release readiness

The recording hot path is now bounded: one 512-sample queue and one worker replace per-event coroutine allocation. Drop behavior is explicit. No profiler or 8-hour runtime was available, so CPU, memory, battery, and thermal performance remain unverified.

Target SDK 37 exceeds the Android 16/API 36 Play submission floor effective 2026-08-31. The `health` foreground service declaration and type-specific permission exist. Runtime `ACTIVITY_RECOGNITION` is requested before recording. Play Console foreground-service declaration, Health apps declaration, public non-PDF privacy policy, Data safety answers, production signing, AAB upload, crash metrics, and accessibility acceptance remain external release blockers.

Authoritative references checked on 2026-10-27:

- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://support.google.com/googleplay/android-developer/answer/18258653

Verdict: not release-ready.
