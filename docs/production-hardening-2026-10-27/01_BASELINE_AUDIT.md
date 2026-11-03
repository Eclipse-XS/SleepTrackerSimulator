# Baseline audit — 2026-10-27

- Base branch: `feature/sleepsense-product-overhaul`.
- Historical hardening lineage tip: `bcfe938312276386c348bb72a4fcdd48615d7d08` (branch subsequently removed).
- Base commit: `0db7afaaf27d466085e54fbe49598fb2f5235ddf`.
- Working tree was dirty before this audit. Existing changes and untracked deliverables were preserved.
- Requested field-feedback file `docs/acceptance/FIELD_FEEDBACK_2026-10-27.md` is absent.
- Modules: `:app`, `:virtualsleepband`; compile/target SDK 37; min SDK 31/26; Room schema v7.
- Baseline `testDebugUnitTest`, both debug assemblies: PASS. Companion unit tests: NO-SOURCE.
- No `adb` executable was available on PATH, therefore no fresh emulator or physical-device run was possible.

Previously generated evidence was treated as historical input, not as proof of this run.
