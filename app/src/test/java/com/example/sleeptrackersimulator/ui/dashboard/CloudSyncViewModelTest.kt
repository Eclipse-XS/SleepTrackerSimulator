package com.example.sleeptrackersimulator.ui.dashboard

import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import com.example.sleeptrackersimulator.cloud.CloudRepository
import com.example.sleeptrackersimulator.cloud.CloudSyncStatus
import com.example.sleeptrackersimulator.cloud.NetworkMonitor
import com.example.sleeptrackersimulator.cloud.PendingPayloadQueue
import com.example.sleeptrackersimulator.cloud.SensorPayload
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.example.sleeptrackersimulator.rest.RestState
import com.example.sleeptrackersimulator.rest.RestStateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is idle and no upload occurs before first interval`() = cloudTest { fixture ->

        assertEquals(CloudSyncStatus.IDLE, fixture.viewModel.state.value.status)
        assertTrue(fixture.viewModel.state.value.monitoringActive)
        advanceTimeBy(4_999)
        runCurrent()

        assertTrue(fixture.repository.uploads.isEmpty())
    }

    @Test
    fun `periodic upload starts at five seconds with current snapshot`() = cloudTest { fixture ->
        fixture.setSensor(x = 1f, y = 2f, z = 3f, intensity = .7f, state = MovementState.MOVEMENT)
        fixture.now = 123L
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        val payload = fixture.repository.uploads.single()
        assertEquals(1f, payload.x)
        assertEquals(2f, payload.y)
        assertEquals(3f, payload.z)
        assertEquals(.7f, payload.value)
        assertEquals("MOVEMENT", payload.movementState)
        assertEquals(123L, payload.timestamp)
    }

    @Test
    fun `starting twice does not create duplicate periodic jobs`() = cloudTest { fixture ->
        fixture.viewModel.startSync()
        fixture.viewModel.startSync()

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, fixture.repository.uploads.size)
    }

    @Test
    fun `BLE disconnected still uploads sensor data`() = cloudTest { fixture ->
        fixture.setSensor(4f, 5f, 6f, .3f, MovementState.STILL)
        fixture.ble.value = BleConnectionState.Disconnected()
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        val payload = fixture.repository.uploads.single()
        assertEquals(4f, payload.x)
        assertEquals(.3f, payload.movementIntensity)
        assertNull(payload.heartRateBpm)
        assertFalse(payload.bleConnected)
    }

    @Test
    fun `BLE connected enriches payload with heart rate and rest state`() = cloudTest { fixture ->
        fixture.ble.value = BleConnectionState.Connected(BleDevice("Band", "VIRTUAL"))
        fixture.heartRate.value = HeartRateMeasurement(68, 1L)
        fixture.rest.value = rest(RestState.RESTING)
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        val payload = fixture.repository.uploads.single()
        assertEquals(68, payload.heartRateBpm)
        assertTrue(payload.bleConnected)
        assertEquals("RESTING", payload.restState)
    }

    @Test
    fun `successful upload transitions from syncing to synced`() = cloudTest { fixture ->
        val gate = CompletableDeferred<Unit>()
        fixture.repository.uploadGate = gate

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CloudSyncStatus.SYNCING, fixture.viewModel.state.value.status)

        gate.complete(Unit)
        runCurrent()
        val state = fixture.viewModel.state.value
        assertEquals(CloudSyncStatus.SYNCED, state.status)
        assertEquals(1, state.uploadedCount)
        assertEquals(0, state.pendingCount)
        assertNull(state.lastErrorMessage)
    }

    @Test
    fun `offline payload becomes pending without repository write`() = cloudTest(online = false) { fixture ->

        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(fixture.repository.uploads.isEmpty())
        assertEquals(CloudSyncStatus.PENDING, fixture.viewModel.state.value.status)
        assertEquals(1, fixture.viewModel.state.value.pendingCount)
    }

    @Test
    fun `network recovery flushes pending payloads in FIFO order`() = cloudTest(online = false) { fixture ->
        repeat(3) { index ->
            fixture.now = (index + 1).toLong()
            advanceTimeBy(5_000)
            runCurrent()
        }
        fixture.network.online = true
        fixture.now = 4L

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L, 4L), fixture.repository.uploads.map { it.timestamp })
        assertEquals(0, fixture.viewModel.state.value.pendingCount)
        assertEquals(CloudSyncStatus.SYNCED, fixture.viewModel.state.value.status)
    }

    @Test
    fun `repository failure transitions to error without cancelling later ticks`() = cloudTest { fixture ->
        fixture.repository.failure = IllegalStateException("write rejected")

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CloudSyncStatus.ERROR, fixture.viewModel.state.value.status)
        assertEquals("write rejected", fixture.viewModel.state.value.lastErrorMessage)

        fixture.repository.failure = null
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CloudSyncStatus.SYNCED, fixture.viewModel.state.value.status)
    }

    @Test
    fun `pending queue remains bounded to fifty and drops oldest`() =
        cloudTest(online = false, queue = PendingPayloadQueue(50)) { fixture ->
        repeat(51) { index ->
            fixture.now = index.toLong()
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertEquals(50, fixture.viewModel.state.value.pendingCount)

        fixture.network.online = true
        fixture.now = 51L
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals((1L..51L).toList(), fixture.repository.uploads.map { it.timestamp })
    }

    @Test
    fun `stop cancels periodic job and restart creates exactly one new job`() = cloudTest { fixture ->
        fixture.viewModel.stopSync()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(fixture.repository.uploads.isEmpty())
        assertFalse(fixture.viewModel.state.value.monitoringActive)

        fixture.viewModel.startSync()
        fixture.viewModel.startSync()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, fixture.repository.uploads.size)
    }

    @Test
    fun `ending monitoring flushes queue writes endedAt and is idempotent`() =
        cloudTest(online = false) { fixture ->
        fixture.now = 10L
        advanceTimeBy(5_000)
        runCurrent()
        fixture.network.online = true
        fixture.now = 20L
        advanceTimeBy(5_000)
        runCurrent()

        fixture.now = 30L
        fixture.viewModel.endMonitoring()
        fixture.viewModel.endMonitoring()
        runCurrent()

        assertEquals(listOf(10L, 20L), fixture.repository.uploads.map { it.timestamp })
        assertEquals(listOf(30L), fixture.repository.endTimes)
        assertEquals(CloudSyncStatus.IDLE, fixture.viewModel.state.value.status)
        assertFalse(fixture.viewModel.state.value.monitoringActive)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, fixture.repository.uploads.size)
    }

    @Test
    fun `ending offline stops locally and keeps session pending`() = cloudTest { fixture ->
        advanceTimeBy(5_000)
        runCurrent()
        fixture.network.online = false

        fixture.viewModel.endMonitoring()
        runCurrent()

        assertTrue(fixture.repository.endTimes.isEmpty())
        assertFalse(fixture.viewModel.state.value.monitoringActive)
        assertEquals(CloudSyncStatus.PENDING, fixture.viewModel.state.value.status)
        assertTrue(fixture.viewModel.state.value.lastErrorMessage!!.contains("offline"))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fixture.repository.uploads.size)
    }

    @Test
    fun `cancellation is not converted to Firebase error`() = cloudTest { fixture ->
        fixture.repository.failure = CancellationException("cancelled")

        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(fixture.viewModel.state.value.status != CloudSyncStatus.ERROR)
        assertNull(fixture.viewModel.state.value.lastErrorMessage)
    }

    private fun cloudTest(
        online: Boolean = true,
        queue: PendingPayloadQueue = PendingPayloadQueue(),
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest(dispatcher) {
        val fixture = Fixture(online, queue)
        try {
            block(fixture)
        } finally {
            fixture.viewModel.stopSync()
            runCurrent()
        }
    }

    private class Fixture(
        online: Boolean = true,
        queue: PendingPayloadQueue = PendingPayloadQueue(),
    ) {
        val repository = FakeCloudRepository()
        val network = FakeNetworkMonitor(online)
        val sensor = MutableStateFlow<SensorUiState>(SensorUiState.Initializing)
        val ble = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
        val heartRate = MutableStateFlow<HeartRateMeasurement?>(null)
        val rest = MutableStateFlow(rest(RestState.MONITORING))
        var now = 0L
        val viewModel = CloudSyncViewModel(
            repository = repository,
            networkMonitor = network,
            sensorState = sensor,
            bleState = ble,
            heartRate = heartRate,
            restState = rest,
            now = { now },
            pendingQueue = queue,
            log = { _, _, _ -> },
        )

        fun setSensor(x: Float, y: Float, z: Float, intensity: Float, state: MovementState) {
            sensor.value = SensorUiState.Active(
                MovementAnalysis(SensorSample(x, y, z, 1L), intensity, state),
            )
        }
    }

    private class FakeNetworkMonitor(var online: Boolean) : NetworkMonitor {
        override fun isOnline(): Boolean = online
    }

    private class FakeCloudRepository : CloudRepository {
        val uploads = mutableListOf<SensorPayload>()
        val endTimes = mutableListOf<Long>()
        var failure: Exception? = null
        var uploadGate: CompletableDeferred<Unit>? = null

        override suspend fun createSession(deviceId: String, startedAt: Long): String = "session-1"

        override suspend fun uploadMeasurement(sessionId: String, payload: SensorPayload) {
            failure?.let { throw it }
            uploadGate?.await()
            uploads += payload
        }

        override suspend fun endSession(sessionId: String, endedAt: Long) {
            failure?.let { throw it }
            endTimes += endedAt
        }
    }

    companion object {
        private fun rest(state: RestState) = RestStateResult.INITIAL.copy(state = state)
    }
}
