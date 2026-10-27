# Final acceptance

## Verdict

`NOT_RELEASE_READY`.

## Implemented

- Bounded, serialized sensor ingestion with synchronous framework-buffer copy.
- Idempotent serialized service commands and stale-event rejection.
- Explicit dropped-sample diagnostics.
- Full-history Insights without the 7/30/90 selector and with load-error state.

## Not verified or not fixed

- Original screen-recorder crash reproduction and physical endurance.
- Physical torch and vibration behavior.
- Main-app Real BLE wiring and truthful synthetic provenance over real transport.
- Autonomous long-lived VirtualSleepBand simulator.
- 90-night demo dataset workflow.
- Independent real-world ML evaluation.
- Instrumentation, physical E2E, Play Console declarations, signing, and production telemetry.

No unresolved item above is represented as successful. The application does not meet the prompt's full acceptance criteria.
