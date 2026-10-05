package com.example.sleeptrackersimulator.rest

import com.example.sleeptrackersimulator.core.model.MovementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestStateClassifierTest {
    private val config = RestStateConfig(
        restingAfterMillis = 1_000L,
        possibleSleepAfterMillis = 5_000L,
        minimumHeartRateSamples = 4,
        heartRateWindowSize = 5,
        maximumStableStandardDeviation = 2.0,
        maximumStableRange = 4,
    )

    private fun classifier() = RestStateClassifier(config, TimeSource { error("Explicit test time required") })

    private fun input(
        movement: MovementState? = MovementState.STILL,
        history: List<Int> = listOf(60, 61, 60, 59, 60),
        heartRate: Int? = history.lastOrNull(),
        available: Boolean = true,
    ) = RestStateInput(movement, 0.05f, heartRate, history, available)

    @Test fun `unavailable heart rate is monitoring`() {
        val result = classifier().classify(input(available = false), 0L)
        assertEquals(RestState.MONITORING, result.state)
    }

    @Test fun `empty heart rate history is monitoring`() {
        val result = classifier().classify(input(history = emptyList(), heartRate = null), 0L)
        assertEquals(RestState.MONITORING, result.state)
        assertEquals(0, result.heartRateSampleCount)
    }

    @Test fun `single heart rate sample is insufficient`() {
        val result = classifier().classify(input(history = listOf(62)), 0L)
        assertEquals(RestState.MONITORING, result.state)
        assertFalse(result.heartRateStable)
    }

    @Test fun `insufficient heart rate samples are monitoring`() {
        val result = classifier().classify(input(history = listOf(60, 61, 60)), 2_000L)
        assertEquals(RestState.MONITORING, result.state)
    }

    @Test fun `movement with heart rate is active regardless of low bpm`() {
        val result = classifier().classify(input(MovementState.MOVEMENT, listOf(45, 45, 45, 45)), 0L)
        assertEquals(RestState.ACTIVE, result.state)
    }

    @Test fun `still before resting threshold remains monitoring`() {
        val subject = classifier()
        subject.classify(input(), 100L)
        val result = subject.classify(input(), 1_099L)
        assertEquals(RestState.MONITORING, result.state)
    }

    @Test fun `still at resting threshold becomes resting`() {
        val subject = classifier()
        subject.classify(input(), 100L)
        val result = subject.classify(input(), 1_100L)
        assertEquals(RestState.RESTING, result.state)
    }

    @Test fun `prolonged stillness with stable heart rate is possible sleep`() {
        val subject = classifier()
        subject.classify(input(), 0L)
        val result = subject.classify(input(), 5_000L)
        assertEquals(RestState.POSSIBLE_SLEEP, result.state)
        assertTrue(result.heartRateStable)
    }

    @Test fun `prolonged stillness with unstable heart rate is not possible sleep`() {
        val subject = classifier()
        val unstable = input(history = listOf(50, 62, 54, 68, 57))
        subject.classify(unstable, 0L)
        val result = subject.classify(unstable, 8_000L)
        assertEquals(RestState.RESTING, result.state)
        assertFalse(result.heartRateStable)
    }

    @Test fun `movement resets still duration`() {
        val subject = classifier()
        subject.classify(input(), 0L)
        subject.classify(input(), 5_000L)
        subject.classify(input(MovementState.MOVEMENT), 5_100L)
        val result = subject.classify(input(), 5_200L)
        assertEquals(0L, result.stillDurationMillis)
        assertEquals(RestState.MONITORING, result.state)
    }

    @Test fun `movement after possible sleep becomes active`() {
        val subject = classifier()
        subject.classify(input(), 0L)
        assertEquals(RestState.POSSIBLE_SLEEP, subject.classify(input(), 5_000L).state)
        assertEquals(RestState.ACTIVE, subject.classify(input(MovementState.MOVEMENT), 5_100L).state)
    }

    @Test fun `absolute bpm alone never produces possible sleep`() {
        val subject = classifier()
        val varyingLowBpm = input(history = listOf(45, 51, 46, 52, 47))
        subject.classify(varyingLowBpm, 0L)
        val result = subject.classify(varyingLowBpm, 10_000L)
        assertEquals(RestState.RESTING, result.state)
    }

    @Test fun `heart rate average uses configured recent window`() {
        val result = classifier().classify(input(history = listOf(100, 100, 58, 60, 62, 60, 60)), 0L)
        assertEquals(60.0, result.recentAverageHeartRate!!, 0.001)
        assertEquals(5, result.heartRateSampleCount)
    }

    @Test fun `population standard deviation calculation is correct`() {
        assertEquals(2.0, listOf(2, 4, 4, 4, 5, 5, 7, 9).populationStandardDeviationOrNull()!!, 0.001)
    }

    @Test fun `heart rate stability respects range boundary`() {
        val boundary = input(history = listOf(60, 64, 62, 61))
        val result = classifier().classify(boundary, 0L)
        assertTrue(result.heartRateStable)
        assertEquals(4, result.heartRateRange)
    }

    @Test fun `configuration thresholds are respected`() {
        val custom = RestStateClassifier(
            config.copy(restingAfterMillis = 10_000L, possibleSleepAfterMillis = 20_000L),
            TimeSource { 0L },
        )
        custom.classify(input(), 0L)
        assertEquals(RestState.MONITORING, custom.classify(input(), 9_999L).state)
        assertEquals(RestState.RESTING, custom.classify(input(), 10_000L).state)
        assertEquals(RestState.POSSIBLE_SLEEP, custom.classify(input(), 20_000L).state)
    }

    @Test fun `missing movement data is monitoring`() {
        val result = classifier().classify(input(movement = null), 10_000L)
        assertEquals(RestState.MONITORING, result.state)
        assertNull(result.recentAverageHeartRate?.takeIf { it.isNaN() })
    }
}
