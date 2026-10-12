package com.example.sleeptrackersimulator.ui.dashboard

import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import com.example.sleeptrackersimulator.cloud.CloudRepository
import com.example.sleeptrackersimulator.cloud.CloudErrorType
import com.example.sleeptrackersimulator.cloud.CloudSyncStatus
import com.example.sleeptrackersimulator.cloud.NetworkMonitor
import com.example.sleeptrackersimulator.cloud.PendingPayloadQueue
import com.example.sleeptrackersimulator.cloud.CloudStateStore
import com.example.sleeptrackersimulator.cloud.InMemoryCloudStateStore
import com.example.sleeptrackersimulator.cloud.AuthRepository
import com.example.sleeptrackersimulator.cloud.ActiveCloudSession
import com.example.sleeptrackersimulator.cloud.CLOUD_DEVICE_ID
import com.example.sleeptrackersimulator.cloud.PendingCloudOperation
import com.example.sleeptrackersimulator.cloud.PendingOperationType
import com.example.sleeptrackersimulator.cloud.EnqueueResult
import com.example.sleeptrackersimulator.cloud.AtomicJsonCloudStateStore
import java.nio.file.Files
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
    fun `unavailable sensor is not uploaded as synthetic zero measurement`() = cloudTest { fixture ->
        fixture.sensor.value = SensorUiState.Unavailable
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(fixture.repository.uploads.isEmpty())
    }

    @Test
    fun `unchanged sensor sample is uploaded only once`() = cloudTest { fixture ->
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, fixture.repository.uploads.size)
    }

    @Test
    fun `network recovery flushes pending payloads in FIFO order`() = cloudTest(online = false) { fixture ->
        repeat(3) { index ->
            fixture.now = (index + 1).toLong()
            fixture.setSensor(1f, 2f, 3f, .2f, MovementState.STILL)
            advanceTimeBy(5_000)
            runCurrent()
        }
        fixture.network.online = true
        fixture.now = 4L
        fixture.setSensor(1f, 2f, 3f, .2f, MovementState.STILL)

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
        cloudTest(online = false, store = InMemoryCloudStateStore(50)) { fixture ->
        repeat(51) { index ->
            fixture.now = (index + 1).toLong()
            fixture.setSensor(1f, 2f, 3f, .2f, MovementState.STILL)
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertEquals(50, fixture.viewModel.state.value.pendingCount)

        fixture.network.online = true
        fixture.now = 51L
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals((2L..51L).toList(), fixture.repository.uploads.map { it.timestamp })
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
        fixture.setSensor(1f, 2f, 3f, .2f, MovementState.STILL)
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
        assertTrue(fixture.viewModel.state.value.lastErrorMessage!!.contains("Pending operations"))
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fixture.repository.uploads.size)
    }

    @Test
    fun `pending offline end recovers automatically when network returns`() = runTest(dispatcher) {
        val store = InMemoryCloudStateStore().apply {
            saveActiveSession(ActiveCloudSession("s", 1L, "uid-1", monitoringActive = false))
            enqueue(PendingCloudOperation("m", PendingOperationType.UPLOAD_MEASUREMENT, "s", SensorPayload("m", CLOUD_DEVICE_ID, "ACCELEROMETER", timestamp = 2L, movementState = "STILL", restState = "MONITORING")))
            enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 3L))
        }
        val fixture = Fixture(false, store)
        runCurrent()
        assertFalse(fixture.viewModel.state.value.monitoringActive)
        fixture.network.online = true
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(2L), fixture.repository.uploads.map { it.timestamp })
        assertEquals(listOf(3L), fixture.repository.endTimes)
        assertEquals(0, store.pendingCount)
        assertNull(store.activeSession())
    }

    @Test
    fun `start cannot reopen a session whose end is pending`() = runTest(dispatcher) {
        val store = InMemoryCloudStateStore().apply {
            saveActiveSession(ActiveCloudSession("s", 1L, "uid-1", monitoringActive = false))
            enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 3L))
        }
        val fixture = Fixture(false, store)
        fixture.viewModel.startSync()
        runCurrent()
        assertFalse(fixture.viewModel.state.value.monitoringActive)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(fixture.repository.uploads.isEmpty())
        fixture.viewModel.stopSync()
    }

    @Test
    fun `pending state from another Firebase uid is not uploaded`() = runTest(dispatcher) {
        val store = InMemoryCloudStateStore().apply {
            saveActiveSession(ActiveCloudSession("s", 1L, "old-uid", monitoringActive = false))
            enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 3L))
        }
        val fixture = Fixture(true, store, authUid = "new-uid")
        runCurrent()
        assertTrue(fixture.repository.endTimes.isEmpty())
        assertEquals(CloudSyncStatus.ERROR, fixture.viewModel.state.value.status)
        assertEquals(CloudErrorType.AUTH, fixture.viewModel.state.value.errorType)
        assertEquals(1, store.pendingCount)
        fixture.viewModel.stopSync()
    }

    @Test
    fun `corrupted persisted state remains visible in ViewModel diagnostics`() = runTest(dispatcher) {
        val file = Files.createTempDirectory("cloud-vm-corrupt").resolve("state.json").toFile()
        file.writeText("{broken")
        val fixture = Fixture(true, AtomicJsonCloudStateStore(file))
        runCurrent()
        assertEquals(CloudSyncStatus.ERROR, fixture.viewModel.state.value.status)
        assertTrue(fixture.viewModel.state.value.storageErrorMessage!!.contains("unreadable"))
        assertTrue(fixture.viewModel.state.value.lastErrorMessage!!.contains("unreadable"))
        fixture.viewModel.stopSync()
    }

    @Test
    fun `enqueue persistence failure becomes error without upload or false success`() = cloudTest(
        store = FailingCloudStateStore(failOn = mutableSetOf("enqueue")),
    ) { fixture ->
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CloudSyncStatus.ERROR, fixture.viewModel.state.value.status)
        assertTrue(fixture.viewModel.state.value.storageErrorMessage!!.contains("simulated enqueue"))
        assertTrue(fixture.repository.uploads.isEmpty())
        assertEquals(0, fixture.viewModel.state.value.uploadedCount)
    }

    @Test
    fun `session persistence failure becomes error instead of escaping ViewModel`() = cloudTest(
        store = FailingCloudStateStore(failOn = mutableSetOf("save")),
    ) { fixture ->
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CloudSyncStatus.ERROR, fixture.viewModel.state.value.status)
        assertTrue(fixture.repository.uploads.isEmpty())
    }

    @Test
    fun `failure while reactivating stored session is reported synchronously`() = runTest(dispatcher) {
        val store = FailingCloudStateStore()
        store.saveActiveSession(ActiveCloudSession("s", 1L, "uid-1", monitoringActive = true))
        store.failOn += "save"
        val fixture = Fixture(true, store)
        runCurrent()
        assertEquals(CloudSyncStatus.ERROR, fixture.viewModel.state.value.status)
        assertFalse(fixture.viewModel.state.value.monitoringActive)
        assertTrue(fixture.repository.uploads.isEmpty())
    }

    @Test
    fun `failure while persisting end request is reported and pending end prevents reuse`() = cloudTest { fixture ->
        advanceTimeBy(5_000)
        runCurrent()
        val store = FailingCloudStateStore()
        val active = requireNotNull(fixture.store.activeSession())
        store.saveActiveSession(active)
        val failingFixture = Fixture(false, store)
        store.failOn += "save"
        failingFixture.viewModel.endMonitoring()
        runCurrent()
        assertEquals(CloudSyncStatus.ERROR, failingFixture.viewModel.state.value.status)
        assertTrue(store.pendingOperations().any { it.type == PendingOperationType.END_SESSION })
        failingFixture.viewModel.startSync()
        runCurrent()
        assertFalse(failingFixture.viewModel.state.value.monitoringActive)
        failingFixture.viewModel.stopSync()
    }

    @Test
    fun `remove failure after Firebase upload reports error and keeps operation for idempotent retry`() = cloudTest(
        store = FailingCloudStateStore(failOn = mutableSetOf("remove")),
    ) { fixture ->
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, fixture.repository.uploads.size)
        assertEquals(CloudSyncStatus.ERROR, fixture.viewModel.state.value.status)
        assertEquals(0, fixture.viewModel.state.value.uploadedCount)
        assertEquals(1, fixture.store.pendingCount)
    }

    @Test
    fun `failed atomic end completion is retried after restart without reusing active session`() = runTest(dispatcher) {
        val store = FailingCloudStateStore(failOn = mutableSetOf("complete"))
        store.saveActiveSession(ActiveCloudSession("s", 1L, "uid-1", monitoringActive = false))
        store.enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 3L))
        val first = Fixture(true, store)
        runCurrent()
        assertEquals(listOf(3L), first.repository.endTimes)
        assertEquals(CloudSyncStatus.ERROR, first.viewModel.state.value.status)
        assertEquals("s", store.activeSession()?.sessionId)
        assertEquals(1, store.pendingCount)
        first.viewModel.stopSync()

        store.failOn.clear()
        val restarted = Fixture(true, store)
        runCurrent()
        assertEquals(listOf(3L), restarted.repository.endTimes)
        assertNull(store.activeSession())
        assertEquals(0, store.pendingCount)
    }

    @Test
    fun `legacy inactive session left after old two-step end cleanup is not reactivated`() = runTest(dispatcher) {
        val store = InMemoryCloudStateStore().apply {
            saveActiveSession(ActiveCloudSession("ended-s", 1L, "uid-1", monitoringActive = false))
        }
        val fixture = Fixture(true, store)
        runCurrent()
        assertNull(store.activeSession())
        assertFalse(fixture.viewModel.state.value.monitoringActive)
        assertTrue(fixture.repository.uploads.isEmpty())
    }

    @Test
    fun `pending end recovers when network returns after ten minutes`() = runTest(dispatcher) {
        val store = InMemoryCloudStateStore().apply {
            saveActiveSession(ActiveCloudSession("s", 1L, "uid-1", monitoringActive = false))
            enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 3L))
        }
        val fixture = Fixture(false, store)
        runCurrent()
        advanceTimeBy(10 * 60 * 1_000L)
        runCurrent()
        assertTrue(fixture.repository.endTimes.isEmpty())
        fixture.network.online = true
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(3L), fixture.repository.endTimes)
        assertNull(store.activeSession())
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
        store: CloudStateStore = InMemoryCloudStateStore(),
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest(dispatcher) {
        val fixture = Fixture(online, store)
        try {
            block(fixture)
        } finally {
            fixture.viewModel.stopSync()
            runCurrent()
        }
    }

    private class Fixture(
        online: Boolean = true,
        store: CloudStateStore = InMemoryCloudStateStore(),
        authUid: String = "uid-1",
    ) {
        val repository = FakeCloudRepository()
        val store = store
        val network = FakeNetworkMonitor(online)
        val sensor = MutableStateFlow<SensorUiState>(
            SensorUiState.Active(
                MovementAnalysis(SensorSample(0f, 0f, 9.81f, 1L), 0f, MovementState.STILL),
            ),
        )
        val ble = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
        val heartRate = MutableStateFlow<HeartRateMeasurement?>(null)
        val rest = MutableStateFlow(rest(RestState.MONITORING))
        var now = 1L
        val viewModel = CloudSyncViewModel(
            repository = repository,
            authRepository = FakeAuthRepository(authUid),
            networkMonitor = network,
            sensorState = sensor,
            bleState = ble,
            heartRate = heartRate,
            restState = rest,
            now = { now },
            stateStore = store,
            newId = { "id-${now}" },
            log = { _, _, _ -> },
        )

        fun setSensor(x: Float, y: Float, z: Float, intensity: Float, state: MovementState) {
            sensor.value = SensorUiState.Active(
                MovementAnalysis(SensorSample(x, y, z, now.coerceAtLeast(1L)), intensity, state),
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

        override suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long) = Unit

        override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) {
            failure?.let { throw it }
            uploadGate?.await()
            uploads += payload
        }

        override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) {
            failure?.let { throw it }
            endTimes += endedAt
        }

        override suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long) = Unit
    }

    private class FakeAuthRepository(private val uid: String) : AuthRepository {
        override suspend fun ensureAuthenticated(): String = uid
        override val currentUid: String = uid
    }

    private class FailingCloudStateStore(
        private val delegate: InMemoryCloudStateStore = InMemoryCloudStateStore(),
        val failOn: MutableSet<String> = mutableSetOf(),
    ) : CloudStateStore {
        override val pendingCount get() = delegate.pendingCount
        override val droppedTelemetryCount get() = delegate.droppedTelemetryCount
        override val loadError: String? get() = delegate.loadError
        override fun enqueue(operation: PendingCloudOperation): EnqueueResult {
            fail("enqueue")
            return delegate.enqueue(operation)
        }
        override fun peek() = delegate.peek()
        override fun removeFirst() { fail("remove"); delegate.removeFirst() }
        override fun pendingOperations() = delegate.pendingOperations()
        override fun activeSession() = delegate.activeSession()
        override fun saveActiveSession(session: ActiveCloudSession?) { fail("save"); delegate.saveActiveSession(session) }
        override fun completeSessionEnd(sessionId: String) { fail("complete"); delegate.completeSessionEnd(sessionId) }
        private fun fail(operation: String) {
            if (failOn.remove(operation)) throw java.io.IOException("simulated $operation persistence failure")
        }
    }

    companion object {
        private fun rest(state: RestState) = RestStateResult.INITIAL.copy(state = state)
    }
}
