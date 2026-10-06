package com.example.sleeptrackersimulator.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudRetentionPolicyTest {
    private val day = 86_400_000L
    @Test fun `active and recent sessions are preserved while expired is selected`() {
        val policy = CloudRetentionPolicy(retentionDays = 30, maxSessions = 30)
        val sessions = listOf(CloudSessionSummary("active", 0, null), CloudSessionSummary("old", day, day + 1), CloudSessionSummary("recent", 39 * day, 39 * day + 1))
        val selected = policy.selectForDeletion(sessions, "active", 40 * day)
        assertEquals(setOf("old"), selected); assertFalse("active" in selected); assertFalse("recent" in selected)
    }
    @Test fun `maximum count keeps newest completed sessions`() {
        val sessions = (1L..5L).map { CloudSessionSummary("s$it", it, it) }
        assertEquals(setOf("s1", "s2"), CloudRetentionPolicy(9999, 3).selectForDeletion(sessions, null, 10))
    }
    @Test fun `unfinished non-active session is not deleted`() {
        assertTrue(CloudRetentionPolicy(1, 1).selectForDeletion(listOf(CloudSessionSummary("open", 0, null)), null, 10 * day).isEmpty())
    }
}
