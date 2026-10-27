# Dataset and Insights

Insights now uses retained summary history rather than a 7/30/90-day selector. Loading failures are represented in state and rendered. The query is summary-level and bounded to 10,000 rows; raw acceleration is not loaded into Compose state.

Existing provenance and baseline gating exclude synthetic source modes. A complete 90-night idempotent generator/import/reset/export workflow was not found and was not fabricated during this run.

- Full-history UI: `FIXED_AND_VERIFIED` by JVM/build gates.
- 90-night demo workflow: `NOT_FIXED`.
- Physical/session UI refresh: `NOT_VERIFIED` without instrumentation runtime.
