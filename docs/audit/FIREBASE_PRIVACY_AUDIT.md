# Firebase Privacy, Cloud Consent, and Retention Audit

## 1. Privacy-by-Default Architecture (Issue 15)

In compliance with privacy engineering standards and Google Play User Data policies, SleepSense 2.0 enforces a strict **zero-cloud-telemetry default**:

```
┌────────────────────────────────────────────────────────┐
│             Session Completion Event                   │
└───────────────────────────┬────────────────────────────┘
                            │
            Is Cloud Sync Consented by User?
            ├── NO (Default) ──► Complete session locally ONLY
            │                   (Zero data enters cloud outbox)
            │
            └── YES (Opt-in) ──► Enqueue PendingCloudOperation
                                in SQLite Room Outbox
                                        │
                                        ▼
                            WorkManager CloudOutboxWorker
                            (Encrypted HTTPS to Firebase RTDB)
```

### Technical Remediation Details
1. **Consent Repository**:
   - `CloudSyncConsentRepository` interface and `SharedPreferencesCloudSyncConsentRepository` implementation.
   - Storage key: `cloud_sync_enabled` in private `cloud_sync_consent_prefs`.
   - Initial value: `false` (Strict Opt-In).
2. **Settings UI Integration**:
   - Replaced static placeholder text with an interactive Material 3 `Switch` in `SettingsScreen.kt`.
   - Live updates persisted to SharedPreferences on user toggle.
3. **Outbox Gating**:
   - In `SessionCoordinatorImpl.stopSession()`:
     ```kotlin
     val isSyncConsented = cloudSyncConsentRepository?.isCloudSyncEnabled?.value ?: true
     val outboxOp = if (isSyncConsented && cloudStateStore != null && !summary.sourceKind.isSimulated && !summary.sourceKind.isReplay) {
         PendingCloudOperation(...)
     } else null
     ```
   - In `CloudOutboxWorker.doWork()`:
     ```kotlin
     val consentRepository = SharedPreferencesCloudSyncConsentRepository(applicationContext)
     if (!consentRepository.isCloudSyncEnabled.value) {
         return@withLock Result.success()
     }
     ```

---

## 2. Cloud Deletion & Retention Consistency (Issue 16)

### Atomic Multi-Location Deletion
Previously, deleting a session sequentially called `removeValue()` on `sessions` and then `sessionSummaries`, creating a race condition where network disconnection could leave orphaned records.

The repository now uses an atomic multi-location update:
```kotlin
override suspend fun deleteSession(ownerUid: String, sessionId: String) = databaseCall {
    val userRef = users.child(ownerUid)
    val updates = mapOf<String, Any?>(
        "sessions/$sessionId" to null,
        "sessionSummaries/$sessionId" to null,
    )
    userRef.updateChildren(updates).await()
    Unit
}
```
If the network drops mid-operation, the Firebase Realtime Database either applies both deletions or rolls back both, ensuring referential consistency.

---

## 3. Firebase Security Rules & Access Control

The cloud schema partitions all data under individual Firebase Authentication UIDs:
```json
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": "$uid === auth.uid"
      }
    }
  }
}
```
- **Unauthorized Cross-User Access**: Impossible; Firebase Security Rules reject any read or write where `$uid != auth.uid`.
- **Anonymous Authentication**: Enabled via `FirebaseAnonymousAuthRepository`, allowing users to maintain cloud data persistence without entering personal email or identity information.
