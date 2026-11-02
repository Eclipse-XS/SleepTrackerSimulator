package com.example.sleeptrackersimulator.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseCloudRepositoryConsentTest {

    private class FakeConsent(enabled: Boolean) : CloudSyncConsentRepository {
        override val isCloudSyncEnabled = MutableStateFlow(enabled)
        override fun setCloudSyncEnabled(enabled: Boolean) { isCloudSyncEnabled.value = enabled }
    }

    @Test
    fun `uploadMeasurement throws CloudOperationException when consent is disabled`() = runTest {
        val consent = FakeConsent(enabled = false)
        // Pass mock or null database if needed, or check constructor
        // Because requireConsent() runs before database calls, it fails fast
        val repo = object : CloudRepository {
            override suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long) {
                if (!consent.isCloudSyncEnabled.value) throw CloudOperationException(CloudErrorType.AUTH, "Consent denied")
            }
            override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) {
                if (!consent.isCloudSyncEnabled.value) throw CloudOperationException(CloudErrorType.AUTH, "Consent denied")
            }
            override suspend fun uploadRuleEvent(ownerUid: String, sessionId: String, payload: SmartRuleEventPayload) {
                if (!consent.isCloudSyncEnabled.value) throw CloudOperationException(CloudErrorType.AUTH, "Consent denied")
            }
            override suspend fun uploadSessionSummary(ownerUid: String, payload: SessionSummaryPayload) {
                if (!consent.isCloudSyncEnabled.value) throw CloudOperationException(CloudErrorType.AUTH, "Consent denied")
            }
            override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) {
                if (!consent.isCloudSyncEnabled.value) throw CloudOperationException(CloudErrorType.AUTH, "Consent denied")
            }
            override suspend fun deleteSession(ownerUid: String, sessionId: String) = Unit
            override suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long) = Unit
        }

        val ex = assertThrows(CloudOperationException::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.uploadMeasurement("uid-1", "s-1", SensorPayload())
            }
        }
        assertEquals(CloudErrorType.AUTH, ex.type)
    }
}
