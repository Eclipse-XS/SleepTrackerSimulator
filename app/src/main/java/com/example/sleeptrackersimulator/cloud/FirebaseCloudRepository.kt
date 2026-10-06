package com.example.sleeptrackersimulator.cloud

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseException
import kotlinx.coroutines.tasks.await

class FirebaseCloudRepository(
    database: FirebaseDatabase = FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL),
    private val retentionPolicy: CloudRetentionPolicy = CloudRetentionPolicy(),
) : CloudRepository {
    private val users = database.reference.child("users")

    override suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long) = databaseCall {
        users.child(ownerUid).child("sessions").child(sessionId).updateChildren(
            mapOf(
                "schemaVersion" to CLOUD_SCHEMA_VERSION,
                "deviceId" to deviceId,
                "startedAt" to startedAt,
                "endedAt" to null,
            ),
        ).await(); Unit
    }

    override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) = databaseCall {
        users.child(ownerUid).child("sessions").child(sessionId).child("measurements")
            .child(payload.measurementId).setValue(payload).await(); Unit
    }

    override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) = databaseCall {
        users.child(ownerUid).child("sessions").child(sessionId).child("endedAt").setValue(endedAt).await(); Unit
    }

    override suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long) = databaseCall {
        val reference = users.child(ownerUid).child("sessions")
        val snapshot = reference.get().await()
        val summaries = snapshot.children.mapNotNull { child ->
            val id = child.key ?: return@mapNotNull null
            val startedAt = child.child("startedAt").getValue(Long::class.java) ?: return@mapNotNull null
            CloudSessionSummary(id, startedAt, child.child("endedAt").getValue(Long::class.java))
        }
        retentionPolicy.selectForDeletion(summaries, activeSessionId, now).forEach { id ->
            reference.child(id).removeValue().await()
        }
    }

    private suspend fun <T> databaseCall(block: suspend () -> T): T = try {
        block()
    } catch (error: DatabaseException) {
        throw CloudOperationException(CloudErrorType.SERVER, "Firebase database operation failed", error)
    }
}
