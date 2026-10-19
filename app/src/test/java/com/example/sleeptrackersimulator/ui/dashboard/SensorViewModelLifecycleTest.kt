package com.example.sleeptrackersimulator.ui.dashboard

import com.example.sleeptrackersimulator.sensor.AccelerometerDataSource
import com.example.sleeptrackersimulator.sensor.AccelerometerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SensorViewModelLifecycleTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `foreground registers one listener pause unregisters and resume registers once`() =
        runTest(dispatcher) {
            val source = CountingDataSource()
            val viewModel = SensorViewModel(source)
            val collector = launch { viewModel.uiState.collect {} }
            runCurrent()
            assertEquals(0, source.activeCollectors)
            assertTrue(viewModel.uiState.value is SensorUiState.Paused)

            viewModel.setForegroundActive(true)
            runCurrent()
            assertEquals(1, source.activeCollectors)
            assertEquals(1, source.registrations)

            viewModel.setForegroundActive(false)
            runCurrent()
            assertEquals(0, source.activeCollectors)

            viewModel.setForegroundActive(true)
            viewModel.setForegroundActive(true)
            runCurrent()
            assertEquals(1, source.activeCollectors)
            assertEquals(2, source.registrations)
            collector.cancel()
        }

    private class CountingDataSource : AccelerometerDataSource {
        var registrations = 0
        var activeCollectors = 0
        override fun observe() = callbackFlow<AccelerometerEvent> {
            registrations++
            activeCollectors++
            awaitClose { activeCollectors-- }
        }
    }

    @Test fun `background unregisters even while multiple downstream subscribers remain`() =
        runTest(dispatcher) {
            val source = CountingDataSource()
            val viewModel = SensorViewModel(source)
            val collectors = List(4) { launch { viewModel.uiState.collect {} } }
            viewModel.setForegroundActive(true)
            runCurrent()
            assertEquals(1, source.activeCollectors)

            repeat(3) {
                viewModel.setForegroundActive(false)
                runCurrent()
                assertEquals(0, source.activeCollectors)
                viewModel.setForegroundActive(true)
                runCurrent()
                assertEquals(1, source.activeCollectors)
            }

            viewModel.setForegroundActive(false)
            runCurrent()
            assertEquals(0, source.activeCollectors)
            collectors.forEach { it.cancel() }
        }
}
