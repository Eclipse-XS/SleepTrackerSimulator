package com.example.sleeptrackersimulator.cloud

interface CloudRepository {
    suspend fun createSession(deviceId: String, startedAt: Long): String
    suspend fun uploadMeasurement(sessionId: String, payload: SensorPayload)
    suspend fun endSession(sessionId: String, endedAt: Long)
}
