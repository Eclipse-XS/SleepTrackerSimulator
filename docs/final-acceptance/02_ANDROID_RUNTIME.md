# Android runtime acceptance — 2026-10-29

Status: `VERIFIED_EMULATOR`

The current debug APKs were installed on `emulator-5554`. SleepSense launched, notification and activity-recognition permissions were granted through the normal UI, and a recording was completed with one pause/resume cycle.

Observed runtime facts:

- Foreground `RecordingService` entered `isForeground=true` with notification id 2026.
- Accelerometer listener registered at 50,000 microseconds (20 Hz).
- Recording continued for 71.346 seconds, excluding the pause boundary.
- Stop removed the recording service and produced a non-empty summary.
- UI summary: 1 minute, 100% coverage, LOW MOTION, 0 observed episodes.
- After an Activity/process restart, History showed exactly one real phone session with 100% coverage.
- No fatal exception was observed in the correlated logcat window.

Screenshot: `screenshots/history_after_restart.png`.

The emulator sensor signal was almost static. This verifies software flow, not human sleep validity.
