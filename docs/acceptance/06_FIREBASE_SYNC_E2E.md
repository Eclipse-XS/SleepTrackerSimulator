# SLEEPSENSE 2.0 — INDEPENDENT ACCEPTANCE AUDIT
## 06. Firebase Realtime Database Security & Sync Verification

**Audit Date:** 2026-10-27  
**Emulator:** `firebase-database-emulator-v4.11.2.jar` (Port 9000)  
**Security Rules:** `database.rules.json`  
**Test Suite:** `firebase-tests/database.rules.test.mjs`

---

### 1. Security Architecture & Rules Guarantee

SleepSense 2.0 operates strictly **Offline-First**. Cloud synchronization is an **opt-in** feature designed under Privacy-by-Design principles:

1. **User Scoping:**
   - All cloud data is partitioned under `/users/{uid}/`.
   - Security rule `.write: "auth != null && auth.uid === $uid"` guarantees that only the authenticated user can access their records.
   - Cross-user reads and writes are rejected at the database engine level.

2. **Session Summary Validation:**
   - Path: `/users/{uid}/sessionSummaries/{sessionId}`.
   - Enforces schema version validation (`schemaVersion === 2`).
   - Validates mathematical bounds on metric fractions ($0.0 \le \text{lowMotionFraction} \le 1.0$).
   - Enforces strict key enumeration (`hasOnly([...])`) to reject arbitrary payloads or code injection.

3. **Smart Rule Idempotence:**
   - Path: `/users/{uid}/sessions/{sessionId}/ruleEvents/{eventId}`.
   - Immutable write-once policy: overwriting or updating an existing `eventId` is rejected by rules (`!data.exists() || data.val() === newData.val()`).

---

### 2. Live Emulator Automated Test Results

Command executed:
```powershell
cd firebase-tests; npm test
```

Result: **11 TESTS PASSED, 0 FAILURES (Duration: 3.17s)**

| # | Test Case Description | Verified Behavior | Status |
|---|---|---|---|
| 1 | `owner can write and read a valid session` | Authenticated owner writes valid session hierarchy | PASS |
| 2 | `unauthenticated and cross-user access is denied` | Unauthenticated requests and cross-UID reads fail | PASS |
| 3 | `unexpected properties and invalid physiological values are denied` | Out-of-bounds metrics and undeclared keys rejected | PASS |
| 4 | `remote controls accept only booleans and known fields` | Type-checked remote actuator triggers accepted | PASS |
| 5 | `owner can write a schema-validated smart-rule event` | Validated smart rule execution record accepted | PASS |
| 6 | `smart-rule validation rejects invalid action, confidence, timestamp` | Out-of-range confidence ($<0$ or $>1$) rejected | PASS |
| 7 | `same event UUID is idempotent; overwrite and cross-user delete rejected` | Re-writing same event succeeds; overwrite rejected | PASS |
| 8 | `invalid and cross-user smart-rule events are denied` | Cross-user injection of rule events fails | PASS |
| 9 | `owner can write and read a valid SleepSense session summary` | High-level session summary accepted for sync | PASS |
| 10 | `session summary validation rejects invalid fractions or extra properties` | Fraction $> 1.0$ or bad keys fail schema validation | PASS |
| 11 | `attacker cannot read or write owner session summaries` | Cross-user snooping rejected with permission denied | PASS |

---

### 3. Client Durable Outbox Architecture

1. **Durable Local Staging:**
   - When offline or cloud sync is disabled, operations are queued in Room `cloud_outbox`.
   - Max capacity: 10,000 operations with FIFO preservation of critical terminal states (`SESSION_END`).

2. **Recovery & Background Sync:**
   - `WorkManagerCloudRecoveryScheduler` triggers background sync upon network availability.
   - Retries with exponential backoff on transient network failures.

**Verdict:** `IMPLEMENTED` & `COMPILED` & `EXECUTED` (11/11 Security Rules tests PASS on live RTDB emulator).
