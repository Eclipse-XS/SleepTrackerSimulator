package com.example.sleeptrackersimulator.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import com.example.sleeptrackersimulator.cloud.*
import com.example.sleeptrackersimulator.rest.RestStateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class CloudSyncViewModel(
    private val repository: CloudRepository,
    private val authRepository: AuthRepository,
    private val networkMonitor: NetworkMonitor,
    sensorState: StateFlow<SensorUiState>,
    bleState: StateFlow<BleConnectionState>,
    heartRate: StateFlow<HeartRateMeasurement?>,
    restState: StateFlow<RestStateResult>,
    private val stateStore: CloudStateStore = InMemoryCloudStateStore(),
    private val validator: SensorPayloadValidator = SensorPayloadValidator(),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val intervalMillis: Long = CLOUD_SYNC_INTERVAL_MS,
    private val log: (String, String, Throwable?) -> Unit = { level, message, error ->
        when (level) { "I" -> Log.i(TAG, message); "W" -> Log.w(TAG, message); else -> Log.e(TAG, message, error) }
    },
) : ViewModel() {
    private data class Snapshot(
        val sensor: SensorUiState,
        val ble: BleConnectionState,
        val heartRate: HeartRateMeasurement?,
        val rest: RestStateResult,
    )

    private val latest = MutableStateFlow(Snapshot(sensorState.value, bleState.value, heartRate.value, restState.value))
    private val _state = MutableStateFlow(CloudSyncUiState(pendingCount = stateStore.pendingCount))
    val state: StateFlow<CloudSyncUiState> = _state
    private val operationMutex = Mutex()
    private var syncJob: Job? = null
    private var recoveryJob: Job? = null

    init {
        viewModelScope.launch { combine(sensorState, bleState, heartRate, restState, ::Snapshot).collect { latest.value = it } }
        val stored = stateStore.activeSession()
        if (stored?.monitoringActive == false) recoverPending()
        else startSync()
    }

    fun startSync() {
        if (syncJob?.isActive == true) return
        stateStore.activeSession()?.let { stateStore.saveActiveSession(it.copy(monitoringActive = true)) }
        _state.update { it.copy(status = CloudSyncStatus.IDLE, monitoringActive = true, pendingCount = stateStore.pendingCount, lastErrorMessage = null) }
        syncJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMillis)
                enqueueMeasurement(latest.value.toPayload(now(), newId()))
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel(); syncJob = null
        _state.update { it.copy(status = CloudSyncStatus.IDLE, monitoringActive = false) }
    }

    fun endMonitoring() {
        if (recoveryJob?.isActive == true) return
        stopSync()
        recoveryJob = viewModelScope.launch {
            operationMutex.withLock {
                val active = stateStore.activeSession() ?: return@withLock
                val endedAt = now()
                stateStore.enqueue(PendingCloudOperation("end-${active.sessionId}", PendingOperationType.END_SESSION, active.sessionId, endedAt = endedAt))
                stateStore.saveActiveSession(active.copy(monitoringActive = false))
                if (!networkMonitor.isOnline()) {
                    pending("Monitoring ended offline; end operation persisted")
                    return@withLock
                }
                flushLocked()
            }
        }
    }

    private fun recoverPending() {
        if (recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            operationMutex.withLock {
                if (networkMonitor.isOnline()) flushLocked() else pending("Pending operations restored from local storage")
            }
        }
    }

    private suspend fun enqueueMeasurement(payload: SensorPayload) {
        operationMutex.withLock {
            val result = validator.validate(payload, now())
            if (!result.valid) {
                error(CloudErrorType.VALIDATION, "Payload rejected: ${result.reason}")
                log("W", "Cloud payload rejected by validation", null)
                return@withLock
            }
            val active = ensureSessionLocked()
            stateStore.enqueue(PendingCloudOperation(payload.measurementId, PendingOperationType.UPLOAD_MEASUREMENT, active.sessionId, payload = payload))
            if (!networkMonitor.isOnline()) {
                pending("No validated internet connection")
                return@withLock
            }
            flushLocked()
        }
    }

    private suspend fun ensureSessionLocked(): ActiveCloudSession {
        stateStore.activeSession()?.let { return it }
        val uid = authRepository.currentUid.orEmpty()
        val session = ActiveCloudSession(newId(), now(), uid, true)
        stateStore.saveActiveSession(session)
        _state.update { it.copy(sessionId = session.sessionId, authenticated = uid.isNotBlank()) }
        return session
    }

    private suspend fun authenticate(): String = try {
        authRepository.ensureAuthenticated().also { uid -> _state.update { it.copy(authenticated = true) } }
    } catch (failure: CloudOperationException) {
        error(failure.type, failure.message ?: "Authentication failed")
        throw failure
    } catch (failure: Exception) {
        error(CloudErrorType.UNKNOWN, failure.message ?: "Authentication failed")
        throw CloudOperationException(CloudErrorType.UNKNOWN, "Authentication failed", failure)
    }

    private suspend fun flushLocked() {
        if (stateStore.pendingCount == 0) return
        var session = stateStore.activeSession() ?: return
        val uid = if (session.ownerUid.isNotBlank()) {
            session.ownerUid
        } else {
            try {
                authenticate().also {
                    session = session.copy(ownerUid = it)
                    stateStore.saveActiveSession(session)
                }
            } catch (_: CloudOperationException) {
                return
            }
        }
        _state.update { it.copy(status = CloudSyncStatus.SYNCING, authenticated = true, lastErrorMessage = null) }
        try {
            retryPolicy.execute { repository.createSession(uid, session.sessionId, CLOUD_DEVICE_ID, session.startedAt) }
        } catch (failure: CloudOperationException) {
            error(failure.type, failure.message ?: "Session creation failed")
            return
        }
        while (true) {
            val operation = stateStore.peek() ?: break
            try {
                retryPolicy.execute {
                    when (operation.type) {
                        PendingOperationType.UPLOAD_MEASUREMENT -> repository.uploadMeasurement(uid, operation.sessionId, requireNotNull(operation.payload))
                        PendingOperationType.END_SESSION -> repository.endSession(uid, operation.sessionId, requireNotNull(operation.endedAt))
                    }
                }
                stateStore.removeFirst()
                if (operation.type == PendingOperationType.UPLOAD_MEASUREMENT) recordSuccess()
                else {
                    stateStore.saveActiveSession(null)
                    _state.update { it.copy(status = CloudSyncStatus.IDLE, sessionId = null, monitoringActive = false, pendingCount = stateStore.pendingCount) }
                    runCatching { repository.applyRetention(uid, null, now()) }
                    log("I", "Cloud monitoring session ended", null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudOperationException) {
                error(failure.type, failure.message ?: "Cloud operation failed")
                return
            } catch (failure: Exception) {
                error(CloudErrorType.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
                return
            }
        }
        if (_state.value.monitoringActive) _state.update { it.copy(status = CloudSyncStatus.SYNCED, pendingCount = 0) }
    }

    private fun pending(message: String) {
        _state.update { it.copy(status = CloudSyncStatus.PENDING, pendingCount = stateStore.pendingCount, lastErrorMessage = message, errorType = CloudErrorType.NETWORK) }
        log("W", "Cloud upload pending: network unavailable", null)
    }

    private fun error(type: CloudErrorType, message: String) {
        _state.update { it.copy(status = CloudSyncStatus.ERROR, pendingCount = stateStore.pendingCount, lastErrorMessage = message, errorType = type) }
        log("E", "Cloud operation failed: $type", null)
    }

    private fun recordSuccess() {
        _state.update { it.copy(status = CloudSyncStatus.SYNCED, lastSuccessfulSyncAt = now(), uploadedCount = it.uploadedCount + 1, pendingCount = stateStore.pendingCount, lastErrorMessage = null, errorType = null) }
        log("I", "Cloud upload successful", null)
    }

    private fun Snapshot.toPayload(timestamp: Long, measurementId: String): SensorPayload {
        val analysis = (sensor as? SensorUiState.Active)?.analysis
        return SensorPayload(
            measurementId = measurementId, deviceId = CLOUD_DEVICE_ID, sensorType = "ACCELEROMETER",
            x = analysis?.sample?.x ?: 0f, y = analysis?.sample?.y ?: 0f, z = analysis?.sample?.z ?: 0f,
            value = analysis?.motionIntensity ?: 0f, timestamp = timestamp,
            movementState = analysis?.state?.name ?: "UNAVAILABLE", movementIntensity = analysis?.motionIntensity ?: 0f,
            heartRateBpm = heartRate?.beatsPerMinute, bleConnected = ble is BleConnectionState.Connected,
            restState = rest.state.name,
        )
    }

    companion object {
        private const val TAG = "CloudSync"
        fun factory(
            repository: CloudRepository, authRepository: AuthRepository, networkMonitor: NetworkMonitor,
            stateStore: CloudStateStore, sensorState: StateFlow<SensorUiState>, bleState: StateFlow<BleConnectionState>,
            heartRate: StateFlow<HeartRateMeasurement?>, restState: StateFlow<RestStateResult>,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CloudSyncViewModel::class.java))
                return CloudSyncViewModel(repository, authRepository, networkMonitor, sensorState, bleState, heartRate, restState, stateStore) as T
            }
        }
    }
}
