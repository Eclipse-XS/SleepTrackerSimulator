package com.example.sleeptrackersimulator.cloud

interface CloudRepository {
    suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long)
    suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload)
    suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long)
    suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long)
}
