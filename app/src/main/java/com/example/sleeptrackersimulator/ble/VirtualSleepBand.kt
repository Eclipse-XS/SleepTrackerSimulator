package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

fun interface HeartRateVariation { fun nextDelta(): Int }

class VirtualSleepBand(
    private val scope: CoroutineScope,
    private val notificationIntervalMillis: Long = 1_500L,
    initialBpm: Int = 62,
    private val minBpm: Int = 45,
    private val maxBpm: Int = 90,
    private val variation: HeartRateVariation = HeartRateVariation { Random.Default.nextInt(-2, 3) },
) {
    val device = BleDevice("Sleep Band HR", "VIRTUAL:HR:01")
    val bodySensorLocation = "Wrist"
    private val _measurements = MutableSharedFlow<HeartRateMeasurement>(extraBufferCapacity = 1)
    val measurements: SharedFlow<HeartRateMeasurement> = _measurements.asSharedFlow()
    private var streamJob: Job? = null
    private var currentBpm = initialBpm.coerceIn(minBpm, maxBpm)
    private var sequence = 0L
    private val _alertState = MutableStateFlow(DeviceAlertState())
    val alertState: StateFlow<DeviceAlertState> = _alertState.asStateFlow()
    val isStreaming: Boolean get() = streamJob?.isActive == true
    val currentMeasurement: HeartRateMeasurement get() = HeartRateMeasurement(currentBpm, sequence)

    init {
        require(notificationIntervalMillis > 0) { "Notification interval must be positive" }
        require(minBpm in 1 until maxBpm) { "Heart-rate range is invalid" }
    }

    fun startStreaming() {
        if (isStreaming) return
        streamJob = scope.launch {
            while (isActive) {
                currentBpm = (currentBpm + variation.nextDelta().coerceIn(-3, 3)).coerceIn(minBpm, maxBpm)
                sequence += 1
                _measurements.emit(HeartRateMeasurement(currentBpm, sequence))
                delay(notificationIntervalMillis)
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    fun processAlert(eventId: String): DeviceAlertState {
        require(eventId.isNotBlank())
        val current = _alertState.value
        if (current.active && current.eventId == eventId) return current
        return DeviceAlertState(true, eventId, System.nanoTime(), current.revision + 1).also { _alertState.value = it }
    }

    fun clearAlert() { _alertState.value = DeviceAlertState(revision = _alertState.value.revision) }

    fun close() = stopStreaming()
}
