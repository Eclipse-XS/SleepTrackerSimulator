package com.example.sleeptrackersimulator.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import com.example.sleeptrackersimulator.cloud.CLOUD_DEVICE_ID
import com.example.sleeptrackersimulator.cloud.CLOUD_SYNC_INTERVAL_MS
import com.example.sleeptrackersimulator.cloud.CloudRepository
import com.example.sleeptrackersimulator.cloud.CloudSyncStatus
import com.example.sleeptrackersimulator.cloud.CloudSyncUiState
import com.example.sleeptrackersimulator.cloud.NetworkMonitor
import com.example.sleeptrackersimulator.cloud.PendingPayloadQueue
import com.example.sleeptrackersimulator.cloud.SensorPayload
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

class CloudSyncViewModel(
    private val repository: CloudRepository,
    private val networkMonitor: NetworkMonitor,
    sensorState: StateFlow<SensorUiState>,
    bleState: StateFlow<BleConnectionState>,
    heartRate: StateFlow<HeartRateMeasurement?>,
    restState: StateFlow<RestStateResult>,
    private val now: () -> Long = System::currentTimeMillis,
    private val intervalMillis: Long = CLOUD_SYNC_INTERVAL_MS,
    private val pendingQueue: PendingPayloadQueue = PendingPayloadQueue(),
    private val log: (String, String, Throwable?) -> Unit = { level, message, error ->
        when (level) {
            "I" -> Log.i(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.e(TAG, message, error)
        }
    },
) : ViewModel() {
    private data class Snapshot(
        val sensor: SensorUiState,
        val ble: BleConnectionState,
        val heartRate: HeartRateMeasurement?,
        val rest: RestStateResult,
    )

    private val latest = MutableStateFlow(
        Snapshot(sensorState.value, bleState.value, heartRate.value, restState.value),
    )
    private val _state = MutableStateFlow(CloudSyncUiState())
    val state: StateFlow<CloudSyncUiState> = _state
    private var sessionId: String? = null
    private var syncJob: Job? = null
    private var endJob: Job? = null

    init {
        viewModelScope.launch {
            combine(sensorState, bleState, heartRate, restState, ::Snapshot).collect { latest.value = it }
        }
        startSync()
    }

    fun startSync() {
        if (syncJob?.isActive == true || endJob?.isActive == true) return
        _state.update { it.copy(status = CloudSyncStatus.IDLE, monitoringActive = true, lastErrorMessage = null) }
        syncJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMillis)
                sync(latest.value.toPayload(now()))
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        _state.update { it.copy(status = CloudSyncStatus.IDLE, monitoringActive = false) }
    }

    fun endMonitoring() {
        if (endJob?.isActive == true) return
        stopSync()
        val activeSession = sessionId ?: return
        endJob = viewModelScope.launch {
            if (!networkMonitor.isOnline()) {
                _state.update {
                    it.copy(
                        status = CloudSyncStatus.PENDING,
                        monitoringActive = false,
                        pendingCount = pendingQueue.size,
                        lastErrorMessage = "Monitoring ended offline; pending data is kept in memory",
                    )
                }
                log("W", "Monitoring ended offline; Firebase session remains open", null)
                return@launch
            }
            _state.update { it.copy(status = CloudSyncStatus.SYNCING, monitoringActive = false) }
            try {
                flushPending(activeSession)
                repository.endSession(activeSession, now())
                sessionId = null
                _state.update {
                    it.copy(
                        status = CloudSyncStatus.IDLE,
                        monitoringActive = false,
                        sessionId = null,
                        pendingCount = pendingQueue.size,
                        lastErrorMessage = null,
                    )
                }
                log("I", "Cloud monitoring session ended", null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        status = CloudSyncStatus.ERROR,
                        monitoringActive = false,
                        pendingCount = pendingQueue.size,
                        lastErrorMessage = error.message ?: error.javaClass.simpleName,
                    )
                }
                log("E", "Cloud session end failed", error)
            }
        }
    }

    private suspend fun sync(payload: SensorPayload) {
        if (!networkMonitor.isOnline()) {
            pendingQueue.add(payload)
            _state.value = _state.value.copy(
                status = CloudSyncStatus.PENDING,
                pendingCount = pendingQueue.size,
                lastErrorMessage = "No validated internet connection",
            )
            log("W", "Cloud upload pending: network unavailable", null)
            return
        }

        _state.update { it.copy(status = CloudSyncStatus.SYNCING, lastErrorMessage = null) }
        try {
            val activeSession = sessionId ?: repository.createSession(CLOUD_DEVICE_ID, now()).also {
                sessionId = it
                _state.update { state -> state.copy(sessionId = it) }
            }
            flushPending(activeSession)
            repository.uploadMeasurement(activeSession, payload)
            recordSuccess()
            log("I", "Cloud upload successful", null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            pendingQueue.add(payload)
            _state.update {
                it.copy(
                    status = CloudSyncStatus.ERROR,
                    pendingCount = pendingQueue.size,
                    lastErrorMessage = error.message ?: error.javaClass.simpleName,
                )
            }
            log("E", "Cloud upload failed", error)
        }
    }

    private suspend fun flushPending(activeSession: String) {
        while (!pendingQueue.isEmpty()) {
            repository.uploadMeasurement(activeSession, pendingQueue.peek())
            pendingQueue.removeFirst()
            recordSuccess()
        }
    }

    private fun recordSuccess() {
        _state.update {
            it.copy(
                status = CloudSyncStatus.SYNCED,
                lastSuccessfulSyncAt = now(),
                uploadedCount = it.uploadedCount + 1,
                pendingCount = pendingQueue.size,
                lastErrorMessage = null,
            )
        }
    }

    private fun Snapshot.toPayload(timestamp: Long): SensorPayload {
        val analysis = (sensor as? SensorUiState.Active)?.analysis
        return SensorPayload(
            deviceId = CLOUD_DEVICE_ID,
            sensorType = "ACCELEROMETER",
            x = analysis?.sample?.x ?: 0f,
            y = analysis?.sample?.y ?: 0f,
            z = analysis?.sample?.z ?: 0f,
            value = analysis?.motionIntensity ?: 0f,
            timestamp = timestamp,
            movementState = analysis?.state?.name ?: "UNAVAILABLE",
            movementIntensity = analysis?.motionIntensity ?: 0f,
            heartRateBpm = heartRate?.beatsPerMinute,
            bleConnected = ble is BleConnectionState.Connected,
            restState = rest.state.name,
        )
    }

    companion object {
        private const val TAG = "CloudSync"

        fun factory(
            repository: CloudRepository,
            networkMonitor: NetworkMonitor,
            sensorState: StateFlow<SensorUiState>,
            bleState: StateFlow<BleConnectionState>,
            heartRate: StateFlow<HeartRateMeasurement?>,
            restState: StateFlow<RestStateResult>,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CloudSyncViewModel::class.java))
                return CloudSyncViewModel(
                    repository, networkMonitor, sensorState, bleState, heartRate, restState,
                ) as T
            }
        }
    }
}
