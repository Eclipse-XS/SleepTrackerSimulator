# SleepSense technical documentation

**Current source of truth:** current Kotlin/Python code, Room schema `8.json`, and `ml/artifacts/deployment_manifest.json`. Historical test reports are not substitutes for these contracts.

- [Architecture and boundaries](ARCHITECTURE.md)
- [Setup and verification](SETUP.md)
- [Virtual wearable: WebSocket versus BLE](VIRTUAL_WEARABLE.md)
- [Android and Python data pipeline](DATA_PIPELINE.md)
- [Deployed ML model card](MODEL_CARD.md)
- [Testing and evidence scope](TESTING.md)
- [Limitations and technical debt](LIMITATIONS.md)

## Visual architecture

1. [System context](diagrams/01-system-context.md)
2. [Android components](diagrams/02-android-components.md)
3. [Sensor / ML / Python data flow](diagrams/03-data-flow.md)
4. [Room v8 relational schema](diagrams/04-room-er.md)
5. [Recording lifecycle](diagrams/05-session-lifecycle.md)
6. [Rules, durable outbox and cloud sync](diagrams/06-rules-cloud-sequence.md)

## Research

Versioned dataset contracts, synthetic-data design, evaluation, domain shift and ablation findings remain in [datascience/](datascience/). Claims in these reports apply to synthetic cohorts unless explicitly stated otherwise.
