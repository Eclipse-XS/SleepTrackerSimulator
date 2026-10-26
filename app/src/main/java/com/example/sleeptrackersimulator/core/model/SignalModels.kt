package com.example.sleeptrackersimulator.core.model

import java.util.UUID
import kotlin.math.sqrt

/**
 * Clock anchor to establish mapping between monotonic elapsedRealtimeNanos and wall-clock UTC.
 */
data class ClockAnchor(
    val elapsedRealtimeNanos: Long,
    val utcTimestampMillis: Long,
) {
    fun toUtcMillis(sampleElapsedNanos: Long): Long {
        val diffNanos = sampleElapsedNanos - elapsedRealtimeNanos
        return utcTimestampMillis + (diffNanos / 1_000_000L)
    }

    companion object {
        fun current(): ClockAnchor = ClockAnchor(
            elapsedRealtimeNanos = runCatching { android.os.SystemClock.elapsedRealtimeNanos() }.getOrDefault(System.nanoTime()),
            utcTimestampMillis = System.currentTimeMillis(),
        )
    }
}

/**
 * Full contract for an individual 3-axis accelerometer sample.
 */
data class RawSensorSample(
    val sampleId: Long = 0,
    val sessionId: String,
    val sourceId: String,
    val elapsedRealtimeNanos: Long,
    val utcTimestampMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val sensorAccuracy: Int = 3, // SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    val sourceKind: SourceKind = SourceKind.PHONE_REAL,
) {
    val magnitude: Float
        get() = sqrt(x * x + y * y + z * z)

    val dynamicAcceleration: Float
        get() = kotlin.math.abs(magnitude - 9.80665f)
}

/**
 * Photoplethysmography / ECG Heart Rate measurement sample.
 */
data class HeartRateSample(
    val sampleId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val deviceId: String,
    val bpm: Int,
    val utcTimestampMillis: Long,
    val timingUncertaintyMillis: Long = 0,
    val sourceKind: SourceKind = SourceKind.BLE_REAL,
)

/**
 * Preprocessed temporal window for feature extraction and ML inference.
 */
data class FeatureWindow(
    val windowId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val sourceId: String,
    val startElapsedNanos: Long,
    val endElapsedNanos: Long,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val sampleCount: Int,
    val expectedSampleCount: Int,
    val coverageFraction: Float,
    val motionRms: Float,
    val variance: Float,
    val jerk: Float,
    val peakToPeak: Float,
    val qualityScore: Float,
    val preprocessingVersion: Int = 1,
)
