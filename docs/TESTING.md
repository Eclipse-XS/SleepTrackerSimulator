# Testing status and evidence boundaries

**Do not treat old acceptance verdicts as live CI results.** Run verification on the exact commit under review.

| Check | Evidence available for baseline `f03008b` | Limitation |
| --- | --- | --- |
| Python Data Science CI | [Successful GitHub Actions run, 2026-11-03](https://github.com/Eclipse-XS/SleepTrackerSimulator/actions/runs/41724340368) | Covers the workflow's Python test/gate commands, not Android hardware |
| Android build and unit tests | Local agent run logs reported successful builds and tests | Not independently reproduced on this documentation branch at authoring time |
| Android emulator/WebSocket | Historical `docs/evidence/targeted-runtime-2026-11-03/TARGETED_ACCEPTANCE.md` in Git history documented a same-emulator HR connection | The raw historical report is removed from current-tree navigation; not a physical BLE test |
| Room v8 | `app/schemas/.../8.json` and migration tests exist | Migration tests must run on the target build/device |
| TFLite | Manifest, assets and golden-vector tests exist | Synthetic parity does not establish real-world model quality |
| Physical BLE / physical actuators | No conclusive physical radio/actuator acceptance in inspected evidence | `NOT_VERIFIED` |
| Live production Firebase | Rules/emulator tests and opt-in outbox code exist | Live cloud credentials/backend E2E `NOT_VERIFIED` |

## Recommended checks

```bash
./gradlew :app:testDebugUnitTest :virtualsleepband:testDebugUnitTest
./gradlew :app:assembleDebug :virtualsleepband:assembleDebug
python -m pytest -q tests
python -m pipelines.cli.main generate --profile SMALL --seed 42
python -m pipelines.cli.main validate
```

On pull requests, the Android workflow runs broader release, lint and Firebase Rules verification. A green Python workflow does not imply the Android workflow passed.

Reports and screenshots are evidence only for the recorded date, device and commit. They do not confer an independent principal-engineer endorsement.
