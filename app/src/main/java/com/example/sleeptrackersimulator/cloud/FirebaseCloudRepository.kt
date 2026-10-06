package com.example.sleeptrackersimulator.cloud

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class FirebaseCloudRepository(
    database: FirebaseDatabase = FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL),
) : CloudRepository {
    private val sessions = database.reference.child("sessions")

    override suspend fun createSession(deviceId: String, startedAt: Long): String {
        val session = sessions.push()
        val sessionId = requireNotNull(session.key) { "Firebase did not generate a session key" }
        session.updateChildren(
            mapOf(
                "deviceId" to deviceId,
                "startedAt" to startedAt,
                "endedAt" to null,
            ),
        ).await()
        return sessionId
    }

    override suspend fun uploadMeasurement(sessionId: String, payload: SensorPayload) {
        sessions.child(sessionId).child("measurements").push().setValue(payload).await()
    }

    override suspend fun endSession(sessionId: String, endedAt: Long) {
        sessions.child(sessionId).child("endedAt").setValue(endedAt).await()
    }
}
