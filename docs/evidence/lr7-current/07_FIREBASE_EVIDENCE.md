# Firebase and offline evidence

Production code використовує FirebaseAuth, Firebase RTDB, owner-scoped paths, Room outbox і WorkManager recovery. Firebase Emulator Rules run 27.10.2026: 11/11 PASS, exit code 0. Це окремий доказ правил, не Android cloud E2E.

Android test `offlineSessionEnd_queuesToRoomOutbox_survivesRestart_andSyncsToRtdbEmulator` PASS. Correlated E2E `sensorThroughTfliteRuleRoomBleOutboxAndFirebaseUsesOneEventId` PASS. Межа доказу: Android emulator + local RTDB emulator; production backend/account/network не перевірено.

Session completion використовує транзакцію summary + `UPLOAD_SESSION_SUMMARY`. Offline failure залишає operation pending; ACK дозволяє її позначити delivered. Smart-rule execution має окремий prepare/finalize protocol; ці гарантії не змішані.
