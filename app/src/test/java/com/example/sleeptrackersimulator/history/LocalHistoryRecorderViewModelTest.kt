package com.example.sleeptrackersimulator.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.sleeptrackersimulator.actuator.ActuatorAction
import com.example.sleeptrackersimulator.actuator.ActuatorActivation
import com.example.sleeptrackersimulator.actuator.TriggerSource
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.example.sleeptrackersimulator.ui.dashboard.SensorUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalHistoryRecorderViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `records at most once per interval and does not duplicate unchanged sample`() = runTest(dispatcher) {
        val fixture = Fixture()
        runCurrent()
        fixture.sensor.value = active(1L, .4f)
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, fixture.repository.records.size)
        assertEquals(.4f, fixture.repository.records.single().sensorValue)
        fixture.close()
    }

    @Test
    fun `successful actuator activation is consumed by exactly one later measurement`() = runTest(dispatcher) {
        val fixture = Fixture()
        runCurrent()
        fixture.activations.emit(
            ActuatorActivation(ActuatorAction.VIBRATION_PULSE, TriggerSource.LOCAL_AUTOMATIC, 900L),
        )
        fixture.sensor.value = active(1L, .7f)
        advanceTimeBy(1_000)
        runCurrent()
        fixture.sensor.value = active(2L, .2f)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, fixture.repository.records.size)
        assertTrue(fixture.repository.records[0].actionTriggered)
        assertFalse(fixture.repository.records[1].actionTriggered)
        fixture.close()
    }

    @Test
    fun `failed insert retains pending activation for retry`() = runTest(dispatcher) {
        val fixture = Fixture()
        runCurrent()
        fixture.activations.emit(
            ActuatorActivation(ActuatorAction.FLASHLIGHT_ENABLED, TriggerSource.MANUAL, 900L),
        )
        fixture.repository.failNextInsert = true
        fixture.sensor.value = active(1L, .8f)
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, fixture.repository.records.size)
        assertTrue(fixture.repository.records.single().actionTriggered)
        fixture.close()
    }

    private inner class Fixture {
        val sensor = MutableStateFlow<SensorUiState>(SensorUiState.Initializing)
        val activations = MutableSharedFlow<ActuatorActivation>(extraBufferCapacity = 4)
        val repository = FakeRepository()
        private val store = ViewModelStore()

        init {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LocalHistoryRecorderViewModel(
                        sensorState = sensor,
                        actuatorActivations = activations,
                        repository = repository,
                        clock = { 1_000L },
                        sampleIntervalMillis = 1_000L,
                        dispatcher = dispatcher,
                    ) as T
            }
            ViewModelProvider(store, factory)[LocalHistoryRecorderViewModel::class.java]
        }

        fun close() = store.clear()
    }

    private class FakeRepository : SensorHistoryRepository {
        val records = mutableListOf<SensorRecord>()
        var failNextInsert = false

        override suspend fun addMeasurement(timestamp: Long, sensorValue: Float, actionTriggered: Boolean): Long {
            if (failNextInsert) {
                failNextInsert = false
                error("simulated insert failure")
            }
            val record = SensorRecord(records.size + 1L, timestamp, sensorValue, actionTriggered)
            records += record
            return record.id
        }

        override fun observeRecent(sinceInclusive: Long, limit: Int): Flow<List<SensorRecord>> =
            MutableStateFlow(records.takeLast(limit))

        override suspend fun recordsBetween(startInclusive: Long, endInclusive: Long) =
            records.filter { it.timestamp in startInclusive..endInclusive }

        override fun observeCount(sinceInclusive: Long): Flow<Int> = MutableStateFlow(records.size)
        override fun observeActionCount(sinceInclusive: Long): Flow<Int> =
            MutableStateFlow(records.count { it.actionTriggered })

        override suspend fun deleteOlderThan(beforeExclusive: Long): Int = 0
    }

    companion object {
        private fun active(timestampNanos: Long, intensity: Float) = SensorUiState.Active(
            MovementAnalysis(
                sample = SensorSample(1f, 2f, 3f, timestampNanos),
                motionIntensity = intensity,
                state = MovementState.MOVEMENT,
            ),
        )
    }
}
