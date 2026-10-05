package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    fun close() = stopStreaming()
}
