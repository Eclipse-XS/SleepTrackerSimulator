# Baseline незалежного аудиту Labs 6–7

- Гілка до публікації: `fix/pre-lab6-reliability`.
- Original HEAD: `f1fb5805cefbebaa39fb87cc4ff357a91459ffb5`.
- Remote `main`: `22f1632`; remote `feature/lab6-on-device-ai`: `916fdda`; remote reliability branch: `f1fb580`.
- Робоче дерево було dirty: локальна реалізація Labs 6–7 не існувала у remote commit.
- Нові локальні компоненти: TFLite REST/RESTLESS/ACTIVE, Room outbox і schema v4, Smart Scenarios, BLE ALERT, Lab 6–7 reports/evidence.

## Незалежно підтверджено

- Модель є executable TFLite; Android instrumentation завантажує її і перевіряє `[1,20,3] → [1,3]`.
- Python training і TFLite metadata використовують порядок осей X/Y/Z та mean-centering усередині graph.
- Room migration chains і DAO перевіряються на AVD.
- Firebase Rules перевіряються Emulator Suite.
- Smart Rules CRUD, cooldown і BLE acknowledgement мають automated tests.

## Знайдено на baseline

- P1: ML window будувався з conflated `StateFlow<SensorUiState>`, тому не гарантував послідовні SensorEvent.
- P1: Smart Rule execution не має окремого durable Firebase payload.
- P2: temporal window не відкидав out-of-order timestamps і великі gaps.
- P2: classifier не перевіряв non-finite/поза `[0,1]` output.
- P2: попередній jank result отримано на debug AVD без workload segmentation, тому він не є release benchmark.
