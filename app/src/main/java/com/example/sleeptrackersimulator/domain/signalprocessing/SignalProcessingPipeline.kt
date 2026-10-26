package com.example.sleeptrackersimulator.domain.signalprocessing

import com.example.sleeptrackersimulator.core.model.ClockAnchor
import com.example.sleeptrackersimulator.core.model.FeatureWindow
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of validating an individual raw accelerometer sample.
 */
sealed class SampleValidationResult {
    data class Valid(val sample: RawSensorSample) : SampleValidationResult()
    data class Invalid(val reason: String) : SampleValidationResult()
}

/**
 * Validates raw samples against physical limits, non-finite values, and timestamps.
 */
class SampleValidator(
    private val maxAccelerationMagnitude: Float = 196.0f, // ~20g limit
) {
    fun validate(
        sessionId: String,
        sourceId: String,
        elapsedNanos: Long,
        utcMillis: Long,
        x: Float,
        y: Float,
        z: Float,
        accuracy: Int,
        sourceKind: SourceKind,
    ): SampleValidationResult {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            return SampleValidationResult.Invalid("Sample contains non-finite values: ($x, $y, $z)")
        }
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude > maxAccelerationMagnitude) {
            return SampleValidationResult.Invalid("Sample exceeds max acceleration threshold: $magnitude m/s^2")
        }
        if (elapsedNanos <= 0 || utcMillis <= 0) {
            return SampleValidationResult.Invalid("Sample contains non-positive timestamp: elapsed=$elapsedNanos, utc=$utcMillis")
        }

        return SampleValidationResult.Valid(
            RawSensorSample(
                sessionId = sessionId,
                sourceId = sourceId,
                elapsedRealtimeNanos = elapsedNanos,
                utcTimestampMillis = utcMillis,
                x = x,
                y = y,
                z = z,
                sensorAccuracy = accuracy,
                sourceKind = sourceKind,
            ),
        )
    }
}

/**
 * Sliding window buffer and feature extractor for raw 3-axis accelerometer samples.
 */
class SlidingWindowSegmenter(
    val windowSize: Int = 20,
    val stride: Int = 20, // non-overlapping by default, matches Lab 6 TFLite window
) {
    private val buffer = ArrayList<RawSensorSample>(windowSize * 2)

    @Synchronized
    fun addSample(sample: RawSensorSample): List<List<RawSensorSample>> {
        buffer.add(sample)
        val readyWindows = mutableListOf<List<RawSensorSample>>()

        while (buffer.size >= windowSize) {
            val window = buffer.subList(0, windowSize).toList()
            readyWindows.add(window)

            // Slide forward by stride
            val dropCount = minOf(stride, buffer.size)
            for (i in 0 until dropCount) {
                buffer.removeAt(0)
            }
        }
        return readyWindows
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}

/**
 * Computes temporal and statistical features from a window of raw accelerometer samples.
 */
object FeatureExtractor {
    private const val GRAVITY = 9.80665f

    fun extractFeatures(
        samples: List<RawSensorSample>,
        expectedSampleCount: Int = 20,
        preprocessingVersion: Int = 1,
    ): FeatureWindow {
        require(samples.isNotEmpty()) { "Cannot extract features from empty samples window" }

        val first = samples.first()
        val last = samples.last()
        val count = samples.size
        val coverage = minOf(1.0f, count.toFloat() / expectedSampleCount)

        // Calculate magnitudes and dynamic acceleration
        var sumDynSq = 0.0
        var sumDyn = 0.0
        var minMag = Float.MAX_VALUE
        var maxMag = Float.MIN_VALUE

        for (s in samples) {
            val mag = s.magnitude
            val dyn = abs(mag - GRAVITY)
            sumDyn += dyn
            sumDynSq += (dyn * dyn)

            if (mag < minMag) minMag = mag
            if (mag > maxMag) maxMag = mag
        }

        val meanDyn = (sumDyn / count).toFloat()
        val rmsDyn = sqrt(sumDynSq / count).toFloat()

        // Variance of dynamic acceleration
        var varianceDyn = 0.0
        for (s in samples) {
            val dyn = abs(s.magnitude - GRAVITY)
            val diff = dyn - meanDyn
            varianceDyn += (diff * diff)
        }
        val variance = if (count > 1) (varianceDyn / (count - 1)).toFloat() else 0.0f

        // Jerk: derivative of acceleration magnitude over time
        var totalJerk = 0.0
        var validJerkIntervals = 0
        for (i in 1 until count) {
            val dtSec = (samples[i].elapsedRealtimeNanos - samples[i - 1].elapsedRealtimeNanos) / 1_000_000_000.0
            if (dtSec > 0.0001) {
                val dMag = abs(samples[i].magnitude - samples[i - 1].magnitude)
                totalJerk += (dMag / dtSec)
                validJerkIntervals++
            }
        }
        val jerk = if (validJerkIntervals > 0) (totalJerk / validJerkIntervals).toFloat() else 0.0f
        val peakToPeak = maxMag - minMag

        // Quality score combines coverage and sensor accuracy
        val avgAccuracy = samples.map { it.sensorAccuracy }.average().toFloat()
        val accuracyFraction = minOf(1.0f, avgAccuracy / 3.0f) // 3 is High Accuracy
        val qualityScore = (coverage * 0.7f) + (accuracyFraction * 0.3f)

        return FeatureWindow(
            sessionId = first.sessionId,
            sourceId = first.sourceId,
            startElapsedNanos = first.elapsedRealtimeNanos,
            endElapsedNanos = last.elapsedRealtimeNanos,
            startUtcMillis = first.utcTimestampMillis,
            endUtcMillis = last.utcTimestampMillis,
            sampleCount = count,
            expectedSampleCount = expectedSampleCount,
            coverageFraction = coverage,
            motionRms = rmsDyn,
            variance = variance,
            jerk = jerk,
            peakToPeak = peakToPeak,
            qualityScore = qualityScore,
            preprocessingVersion = preprocessingVersion,
        )
    }
}
