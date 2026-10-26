package com.example.sleeptrackersimulator.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    @Test
    fun `initial session is in PREPARING status and transitions to RECORDING`() {
        val session = RecordingSession(
            startUtcMillis = 1000L,
            startElapsedRealtimeNanos = 5000L,
        )
        assertEquals(SessionStatus.PREPARING, session.status)
        assertTrue(session.status.isActive)
        assertFalse(session.status.isTerminal)

        val recording = session.withStatus(SessionStatus.RECORDING)
        assertEquals(SessionStatus.RECORDING, recording.status)
        assertTrue(recording.status.isActive)
    }

    @Test
    fun `recording session can pause and resume`() {
        val session = RecordingSession(
            startUtcMillis = 1000L,
            startElapsedRealtimeNanos = 5000L,
            status = SessionStatus.RECORDING,
        )

        val paused = session.withStatus(SessionStatus.PAUSED)
        assertEquals(SessionStatus.PAUSED, paused.status)

        val resumed = paused.withStatus(SessionStatus.RECORDING)
        assertEquals(SessionStatus.RECORDING, resumed.status)
    }

    @Test
    fun `recording session transitions to finalizing and completes`() {
        val session = RecordingSession(
            startUtcMillis = 1000L,
            startElapsedRealtimeNanos = 5000L,
            status = SessionStatus.RECORDING,
        )

        val finalizing = session.withStatus(SessionStatus.FINALIZING)
        assertEquals(SessionStatus.FINALIZING, finalizing.status)

        val completed = finalizing.completed(endUtcMillis = 5000L, coverageFraction = 0.98f)
        assertEquals(SessionStatus.COMPLETED, completed.status)
        assertTrue(completed.isCompleted)
        assertEquals(4000L, completed.durationMillis)
        assertEquals(SessionQualityStatus.EXCELLENT, completed.qualityStatus)
    }

    @Test
    fun `interrupted session can be recovered or finalized`() {
        val session = RecordingSession(
            startUtcMillis = 1000L,
            startElapsedRealtimeNanos = 5000L,
            status = SessionStatus.RECORDING,
        )

        val interrupted = session.withStatus(SessionStatus.INTERRUPTED, "Process killed")
        assertEquals(SessionStatus.INTERRUPTED, interrupted.status)
        assertEquals("Process killed", interrupted.interruptionReason)

        val recoverable = interrupted.withStatus(SessionStatus.RECOVERABLE)
        assertEquals(SessionStatus.RECOVERABLE, recoverable.status)

        val resumed = recoverable.withStatus(SessionStatus.RECORDING)
        assertEquals(SessionStatus.RECORDING, resumed.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `illegal direct transition from IDLE to COMPLETED throws exception`() {
        val session = RecordingSession(
            startUtcMillis = 1000L,
            startElapsedRealtimeNanos = 5000L,
            status = SessionStatus.IDLE,
        )
        session.withStatus(SessionStatus.COMPLETED)
    }
}
