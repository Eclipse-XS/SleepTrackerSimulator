package com.example.sleeptrackersimulator.cloud

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseException
import java.io.IOException
import kotlinx.coroutines.tasks.await

class FirebaseCloudRepository(
    database: FirebaseDatabase = FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL),
    private val retentionPolicy: CloudRetentionPolicy = CloudRetentionPolicy(),
    private val consentRepository: CloudSyncConsentRepository? = null,
) : CloudRepository {
    private val users = database.reference.child("users")

    private fun requireConsent() {
        if (consentRepository != null && !consentRepository.isCloudSyncEnabled.value) {
            throw CloudOperationException(
                CloudErrorType.AUTH,
                "Cloud synchronization rejected: user consent is not granted",
            )
        }
    }

    override suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessions").child(sessionId).updateChildren(
            mapOf(
                "schemaVersion" to CLOUD_SCHEMA_VERSION,
                "deviceId" to deviceId,
                "startedAt" to startedAt,
            ),
        ).await(); Unit
    }

    override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessions").child(sessionId).child("measurements")
            .child(payload.measurementId).setValue(payload).await(); Unit
    }

    override suspend fun uploadRuleEvent(ownerUid: String, sessionId: String, payload: SmartRuleEventPayload) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessions").child(sessionId).child("ruleEvents")
            .child(payload.eventId).setValue(payload).await(); Unit
    }

    override suspend fun uploadSessionSummary(ownerUid: String, payload: SessionSummaryPayload) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessionSummaries").child(payload.sessionId).setValue(payload).await(); Unit
    }

    override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessions").child(sessionId).child("endedAt").setValue(endedAt).await(); Unit
    }

    override suspend fun deleteSession(ownerUid: String, sessionId: String) = databaseCall {
        val userRef = users.child(ownerUid)
        val updates = mapOf<String, Any?>(
            "sessions/$sessionId" to null,
            "sessionSummaries/$sessionId" to null,
        )
        userRef.updateChildren(updates).await()
        Unit
    }

    override suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long) = databaseCall {
        val reference = users.child(ownerUid).child("sessions")
        val snapshot = reference.get().await()
        val summaries = snapshot.children.mapNotNull { child ->
            val id = child.key ?: return@mapNotNull null
            val startedAt = child.child("startedAt").getValue(Long::class.java) ?: return@mapNotNull null
            CloudSessionSummary(id, startedAt, child.child("endedAt").getValue(Long::class.java))
        }
        val userRef = users.child(ownerUid)
        val toDelete = retentionPolicy.selectForDeletion(summaries, activeSessionId, now)
        if (toDelete.isNotEmpty()) {
            val updates = mutableMapOf<String, Any?>()
            for (id in toDelete) {
                updates["sessions/$id"] = null
                updates["sessionSummaries/$id"] = null
            }
            userRef.updateChildren(updates).await()
        }
    }

    private suspend fun <T> databaseCall(block: suspend () -> T): T = try {
        block()
    } catch (error: Exception) {
        if (error is CloudOperationException) throw error
        val type = classifyFirebaseFailure(error)
        throw CloudOperationException(type, "Firebase database operation failed: ${error.message ?: error.javaClass.simpleName}", error)
    }
}

internal fun classifyFirebaseFailure(error: Throwable): CloudErrorType {
    val chain = generateSequence(error as Throwable?) { it.cause }
    val message = chain.joinToString(" ") { "${it.javaClass.name} ${it.message.orEmpty()}" }.lowercase()
    return when {
        "permission denied" in message || "permission_denied" in message -> CloudErrorType.PERMISSION
        "unauth" in message || "auth" in message && "network" !in message -> CloudErrorType.AUTH
        "invalid" in message || "validation" in message || "failed to parse" in message -> CloudErrorType.VALIDATION
        error is IOException || "network" in message || "disconnected" in message || "timeout" in message -> CloudErrorType.NETWORK
        error is DatabaseException -> CloudErrorType.SERVER
        else -> CloudErrorType.UNKNOWN
    }
}
