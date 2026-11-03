package com.example.sleeptrackersimulator.cloud

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseException
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        users.child(ownerUid).child("sessions").child(sessionId).awaitUpdateChildren(
            mapOf(
                "schemaVersion" to CLOUD_SCHEMA_VERSION,
                "deviceId" to deviceId,
                "startedAt" to startedAt,
            ),
        )
    }

    override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessions").child(sessionId).child("measurements")
            .child(payload.measurementId).awaitSetValue(payload)
    }

    override suspend fun uploadRuleEvent(ownerUid: String, sessionId: String, payload: SmartRuleEventPayload) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessions").child(sessionId).child("ruleEvents")
            .child(payload.eventId).awaitSetValue(payload)
    }

    override suspend fun uploadSessionSummary(ownerUid: String, payload: SessionSummaryPayload) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessionSummaries").child(payload.sessionId).awaitSetValue(payload)
    }

    override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) = databaseCall {
        requireConsent()
        users.child(ownerUid).child("sessions").child(sessionId).child("endedAt").awaitSetValue(endedAt)
    }

    override suspend fun deleteSession(ownerUid: String, sessionId: String) = databaseCall {
        val userRef = users.child(ownerUid)
        val updates = mapOf<String, Any?>(
            "sessions/$sessionId" to null,
            "sessionSummaries/$sessionId" to null,
        )
        userRef.awaitUpdateChildren(updates)
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
            userRef.awaitUpdateChildren(updates)
        }
    }

    private suspend fun <T> databaseCall(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (error is CloudOperationException) throw error
        val type = classifyFirebaseFailure(error)
        throw CloudOperationException(type, "Firebase database operation failed: ${error.message ?: error.javaClass.simpleName}", error)
    }
}

internal fun classifyFirebaseFailure(error: Throwable): CloudErrorType {
    val structured = generateSequence(error as Throwable?) { it.cause }
        .filterIsInstance<FirebaseDatabaseErrorException>()
        .firstOrNull()
    if (structured != null) return when (structured.code) {
        DatabaseError.PERMISSION_DENIED -> CloudErrorType.PERMISSION
        DatabaseError.EXPIRED_TOKEN, DatabaseError.INVALID_TOKEN -> CloudErrorType.AUTH
        DatabaseError.DISCONNECTED, DatabaseError.NETWORK_ERROR, DatabaseError.WRITE_CANCELED -> CloudErrorType.NETWORK
        DatabaseError.UNAVAILABLE, DatabaseError.OPERATION_FAILED, DatabaseError.MAX_RETRIES -> CloudErrorType.SERVER
        DatabaseError.USER_CODE_EXCEPTION -> CloudErrorType.VALIDATION
        else -> CloudErrorType.UNKNOWN
    }
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

private class FirebaseDatabaseErrorException(val code: Int, message: String) : IOException(message)

private suspend fun DatabaseReference.awaitUpdateChildren(values: Map<String, Any?>) =
    suspendCancellableCoroutine<Unit> { continuation ->
        updateChildren(values) { error, _ ->
            if (!continuation.isActive) return@updateChildren
            if (error == null) continuation.resume(Unit)
            else continuation.resumeWithException(FirebaseDatabaseErrorException(error.code, error.message))
        }
    }

private suspend fun DatabaseReference.awaitSetValue(value: Any?) =
    suspendCancellableCoroutine<Unit> { continuation ->
        setValue(value) { error, _ ->
            if (!continuation.isActive) return@setValue
            if (error == null) continuation.resume(Unit)
            else continuation.resumeWithException(FirebaseDatabaseErrorException(error.code, error.message))
        }
    }
