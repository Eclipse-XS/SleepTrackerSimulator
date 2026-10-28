# Final acceptance

## Verdict

`IMPLEMENTED_AWAITING_PHYSICAL_VERIFICATION` — not approved for production release.

## Correctness status

- Demo and real Insights are isolated. Ninety synthetic nights populate duration, low-motion, movement, BPM, coverage and chronological trend views. One real session exposes descriptive metrics without creating a personal baseline.
- Insights observes Room summaries, so import, reset and completed-session writes refresh without manual reconstruction.
- Demo dates use an injected `Clock`, local calendar days and a non-future end date. Deterministic metric generation and IDs are preserved.
- History reads persisted summary provenance. Missing wearable provenance falls back to `BLE_UNVERIFIED`, never `BLE_REAL`.
- Room verification reads stored sessions and summaries and checks IDs, cardinality, provenance, time bounds, duration, coverage, movement-rate consistency and orphan/missing summaries.
- BLE client rejects stale GATT callbacks, waits for CCCD acknowledgement, serializes transport switching and applies connection/scan timeouts.
- VirtualSleepBand registers Heart Rate then Battery service asynchronously before advertising; stale and out-of-order callbacks are rejected by a tested sequencer.
- Manual flashlight OFF remains authoritative over stale remote ON state. Vibration diagnostics explicitly report `physicalEffectConfirmed=false`.
- ML evaluation helpers reject malformed splits, recording leakage, empty or incompatible arrays, invalid bins, non-normalized probabilities and invalid labels.

## Executed local evidence

- 223 JVM tests: PASS, 0 failed, 0 skipped.
- 27 emulator instrumentation/UI tests: PASS, 0 failed, 0 skipped.
- 11 Firebase Database Emulator tests: PASS.
- 8 Python evaluation-quality tests: PASS.
- App and VirtualSleepBand debug/release assembly: PASS.
- App and VirtualSleepBand debug/release lint: PASS.
- Dataset CSV ingestion verification: PASS; synthetic benchmark only.

## Release blockers

- Physical two-phone BLE discovery, CCCD subscription, packet continuity, disconnect and reconnect.
- Physical torch and vibration observation on supported hardware.
- Reproduction of the original screen-recorder failure condition.
- Independent real-world ML validation. No real-user 98% accuracy claim is supported.
- Production signing, Play Console declarations and release telemetry review.

GitHub Actions status is recorded separately after the final commit is pushed; a queued or merely started workflow is not counted as PASS.
