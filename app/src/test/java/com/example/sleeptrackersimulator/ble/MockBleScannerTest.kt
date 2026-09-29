package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockBleScannerTest {

    @Test
    fun `scan returns no result before discovery delay and fixed device after delay`() = runTest {
        val scanner = MockBleScanner(discoveryDelayMillis = 2_000L)
        val scanDeferred = async { scanner.scan() }

        runCurrent()
        advanceTimeBy(1_999L)
        runCurrent()
        assertFalse(scanDeferred.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(scanDeferred.isCompleted)
        val result = scanDeferred.await()
        assertEquals(listOf(MockBleScanner.VIRTUAL_DEVICE), result)
    }

    @Test
    fun `cancelled scan does not return a device`() = runTest {
        val scanner = MockBleScanner(discoveryDelayMillis = 2_000L)
        var consumedResult: List<BleDevice>? = null
        val job = launch {
            consumedResult = scanner.scan()
        }

        runCurrent()
        job.cancel()
        advanceTimeBy(2_000L)
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(null, consumedResult)
    }
}
