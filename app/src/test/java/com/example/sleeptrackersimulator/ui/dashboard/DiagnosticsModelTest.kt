package com.example.sleeptrackersimulator.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsModelTest {
    @Test fun noSampleIsNeverLive() {
        assertEquals(HrStreamHealth.NO_SAMPLES, DiagnosticsModel.hrStreamHealth(null, 10_000_000_000L))
        assertTrue(DiagnosticsModel.healthLabel(HrStreamHealth.NO_SAMPLES, subscribed = true).contains("NO HR SAMPLES"))
    }

    @Test fun recentVsStale() {
        val now = 100_000_000_000L
        assertEquals(HrStreamHealth.LIVE, DiagnosticsModel.hrStreamHealth(now - 1_000_000_000L, now))
        assertEquals(HrStreamHealth.STALE, DiagnosticsModel.hrStreamHealth(now - 6_000_000_000L, now))
        assertTrue(DiagnosticsModel.healthLabel(HrStreamHealth.STALE, true).contains("NO RECENT"))
    }

    @Test fun cloudErrorSummaryIncludesPendingAndCause() {
        assertNull(DiagnosticsModel.cloudErrorSummary(3, null, null))
        val s = DiagnosticsModel.cloudErrorSummary(3, "Permission denied", null)!!
        assertTrue(s.contains("3 pending") && s.contains("Permission denied"))
    }
}
