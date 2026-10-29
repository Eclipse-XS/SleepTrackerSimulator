package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

enum class InferenceMode {
    ACCELEROMETER_TFLITE,
    MULTIMODAL_TFLITE,
    RULE_BASED_FALLBACK,
    INSUFFICIENT_DATA,
}

data class ModelSelectionResult(
    val predictionId: String = UUID.randomUUID().toString(),
    val activity: ActivityClass,
    val probabilities: Map<ActivityClass, Float>,
    val confidence: Float,
    val inferenceMode: InferenceMode,
    val modelId: String,
    val modelVersion: String,
    val fallbackReason: String? = null,
    val qualityFlags: Set<String> = emptySet(),
    val inferenceDurationNs: Long = 0L,
    val windowEndTimestampNanos: Long = 0L,
)

interface ModelSelectionEngine {
    val currentMode: InferenceMode

    fun selectAndClassify(
        samples: List<RawSensorSample>,
        recentHeartRate: HeartRateSample? = null,
        windowEndTimestampNanos: Long = System.nanoTime(),
    ): ModelSelectionResult
}

class DefaultModelSelectionEngine(
    private val activityClassifier: ActivityClassifier?,
    private val multimodalClassifier: MultimodalClassifier? = null,
    private val minConfidenceThreshold: Float = 0.35f,
    private val maxHeartRateStalenessMillis: Long = 10_000L,
) : ModelSelectionEngine {

    private val activeModeRef = AtomicReference(InferenceMode.INSUFFICIENT_DATA)
    override val currentMode: InferenceMode
        get() = activeModeRef.get()

    override fun selectAndClassify(
        samples: List<RawSensorSample>,
        recentHeartRate: HeartRateSample?,
        windowEndTimestampNanos: Long,
    ): ModelSelectionResult {
        // 1. Validate sample window
        if (samples.size < 20) {
            val result = ModelSelectionResult(
                activity = ActivityClass.UNCERTAIN,
                probabilities = mapOf(ActivityClass.UNCERTAIN to 1.0f),
                confidence = 0f,
                inferenceMode = InferenceMode.INSUFFICIENT_DATA,
                modelId = "none",
                modelVersion = "none",
                fallbackReason = "insufficient_samples: ${samples.size}/20",
                qualityFlags = setOf("INCOMPLETE_WINDOW"),
                windowEndTimestampNanos = windowEndTimestampNanos,
            )
            activeModeRef.set(InferenceMode.INSUFFICIENT_DATA)
            return result
        }

        // Validate finite values
        val hasNonFinite = samples.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }
        if (hasNonFinite) {
            val result = ModelSelectionResult(
                activity = ActivityClass.UNCERTAIN,
                probabilities = mapOf(ActivityClass.UNCERTAIN to 1.0f),
                confidence = 0f,
                inferenceMode = InferenceMode.INSUFFICIENT_DATA,
                modelId = "none",
                modelVersion = "none",
                fallbackReason = "corrupted_sensor_sample: non-finite acceleration values",
                qualityFlags = setOf("NON_FINITE_SAMPLE"),
                windowEndTimestampNanos = windowEndTimestampNanos,
            )
            activeModeRef.set(InferenceMode.INSUFFICIENT_DATA)
            return result
        }

        // Collect quality flags
        val qualityFlags = mutableSetOf<String>()
        val avgAccuracy = samples.map { it.sensorAccuracy }.average()
        if (avgAccuracy < 1.0) qualityFlags.add("LOW_SENSOR_ACCURACY")
        if (samples.any { !it.sourceKind.isRealData }) qualityFlags.add("SIMULATED_ACCELEROMETER")

        val inputWindow = Array(20) { i ->
            floatArrayOf(samples[i].x, samples[i].y, samples[i].z)
        }

        var fallbackReason: String? = null

        // 2. Try Multimodal TFLite Classifier (Late fusion of Accel + BLE Heart Rate)
        if (multimodalClassifier != null && recentHeartRate != null) {
            if (!recentHeartRate.sourceKind.isRealData) {
                qualityFlags.add("SIMULATED_HEART_RATE")
            }
            val refTimeMs = samples.lastOrNull()?.utcTimestampMillis ?: System.currentTimeMillis()
            val hrAgeMs = kotlin.math.abs(refTimeMs - recentHeartRate.utcTimestampMillis)
            val isHrFresh = hrAgeMs <= maxHeartRateStalenessMillis
            val isHrValid = recentHeartRate.bpm in 30..240

            if (isHrFresh && isHrValid) {
                try {
                    val pred = multimodalClassifier.classify(
                        accelerometerWindow = inputWindow,
                        heartRateBpm = recentHeartRate.bpm.toFloat(),
                        isHeartRateFresh = true,
                        windowEndTimestampNanos = windowEndTimestampNanos,
                    )
                    val domainClass = ActivityClass.fromString(pred.activity.name)
                    val domainProbs = pred.probabilities.mapKeys { ActivityClass.fromString(it.key.name) }

                    if (pred.confidence >= minConfidenceThreshold && domainClass != ActivityClass.UNCERTAIN) {
                        activeModeRef.set(InferenceMode.MULTIMODAL_TFLITE)
                        return ModelSelectionResult(
                            predictionId = pred.predictionId,
                            activity = domainClass,
                            probabilities = domainProbs,
                            confidence = pred.confidence,
                            inferenceMode = InferenceMode.MULTIMODAL_TFLITE,
                            modelId = "multimodal-tflite-v1.0",
                            modelVersion = "multimodal-v1.0",
                            fallbackReason = null,
                            qualityFlags = qualityFlags.toSet(),
                            inferenceDurationNs = pred.inferenceNanos,
                            windowEndTimestampNanos = windowEndTimestampNanos,
                        )
                    } else {
                        fallbackReason = "multimodal_low_confidence_${String.format("%.2f", pred.confidence)}"
                    }
                } catch (t: Throwable) {
                    fallbackReason = "multimodal_inference_failed: ${t.message ?: t.javaClass.simpleName}"
                }
            } else if (!isHrFresh) {
                qualityFlags.add("STALE_HEART_RATE")
                fallbackReason = "stale_heart_rate_${hrAgeMs}ms"
            } else {
                qualityFlags.add("INVALID_HEART_RATE")
                fallbackReason = "invalid_heart_rate_${recentHeartRate.bpm}bpm"
            }
        } else if (recentHeartRate == null) {
            qualityFlags.add("NO_HEART_RATE_AVAILABLE")
        }

        // 3. Try Accelerometer-Only TFLite Classifier
        if (activityClassifier != null) {
            try {
                val pred = activityClassifier.classify(inputWindow, windowEndTimestampNanos)
                val domainClass = ActivityClass.fromString(pred.activity.name)
                val domainProbs = pred.probabilities.mapKeys { ActivityClass.fromString(it.key.name) }

                if (pred.confidence >= minConfidenceThreshold && domainClass != ActivityClass.UNCERTAIN) {
                    activeModeRef.set(InferenceMode.ACCELEROMETER_TFLITE)
                    return ModelSelectionResult(
                        predictionId = pred.predictionId,
                        activity = domainClass,
                        probabilities = domainProbs,
                        confidence = pred.confidence,
                        inferenceMode = InferenceMode.ACCELEROMETER_TFLITE,
                        modelId = "activity-v2.1",
                        modelVersion = "activity-v2.1",
                        fallbackReason = fallbackReason,
                        qualityFlags = qualityFlags,
                        inferenceDurationNs = pred.inferenceNanos,
                        windowEndTimestampNanos = windowEndTimestampNanos,
                    )
                } else {
                    fallbackReason = "tflite_low_confidence_${String.format("%.2f", pred.confidence)}"
                }
            } catch (t: Throwable) {
                fallbackReason = "tflite_inference_failed: ${t.message ?: t.javaClass.simpleName}"
            }
        } else {
            fallbackReason = fallbackReason ?: "tflite_model_unavailable"
        }

        // 4. Deterministic Heuristic Fallback on Physical RMS and Variance
        val fallbackStartNs = System.nanoTime()
        val (fallbackClass, fallbackConf, fallbackProbs) = computeRuleBasedFallback(samples)
        val fallbackDurationNs = System.nanoTime() - fallbackStartNs

        activeModeRef.set(InferenceMode.RULE_BASED_FALLBACK)
        return ModelSelectionResult(
            predictionId = "fallback-$windowEndTimestampNanos",
            activity = fallbackClass,
            probabilities = fallbackProbs,
            confidence = fallbackConf,
            inferenceMode = InferenceMode.RULE_BASED_FALLBACK,
            modelId = "rule-based-fallback",
            modelVersion = "heuristics-v1.0",
            fallbackReason = fallbackReason,
            qualityFlags = qualityFlags + "FALLBACK_ENGAGED",
            inferenceDurationNs = fallbackDurationNs,
            windowEndTimestampNanos = windowEndTimestampNanos,
        )
    }

    private fun computeRuleBasedFallback(samples: List<RawSensorSample>): Triple<ActivityClass, Float, Map<ActivityClass, Float>> {
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        for (s in samples) {
            sumX += s.x
            sumY += s.y
            sumZ += s.z
        }
        val meanX = (sumX / samples.size).toFloat()
        val meanY = (sumY / samples.size).toFloat()
        val meanZ = (sumZ / samples.size).toFloat()

        var sumDynSq = 0.0
        var sumMag = 0.0
        val dynMags = FloatArray(samples.size)
        for (i in samples.indices) {
            val dx = samples[i].x - meanX
            val dy = samples[i].y - meanY
            val dz = samples[i].z - meanZ
            val m = sqrt(dx * dx + dy * dy + dz * dz)
            dynMags[i] = m
            sumMag += m
            sumDynSq += (m * m)
        }
        val rms = sqrt(sumDynSq / samples.size).toFloat()
        val meanMag = (sumMag / samples.size).toFloat()
        var varSum = 0.0
        for (m in dynMags) {
            val diff = m - meanMag
            varSum += (diff * diff)
        }
        val variance = (varSum / samples.size).toFloat()

        return when {
            rms < 0.10f && variance < 0.015f -> Triple(
                ActivityClass.REST,
                0.90f,
                mapOf(
                    ActivityClass.REST to 0.90f,
                    ActivityClass.RESTLESS to 0.08f,
                    ActivityClass.ACTIVE to 0.02f,
                    ActivityClass.UNCERTAIN to 0.00f,
                ),
            )
            rms < 0.85f -> Triple(
                ActivityClass.RESTLESS,
                0.80f,
                mapOf(
                    ActivityClass.REST to 0.12f,
                    ActivityClass.RESTLESS to 0.80f,
                    ActivityClass.ACTIVE to 0.08f,
                    ActivityClass.UNCERTAIN to 0.00f,
                ),
            )
            else -> Triple(
                ActivityClass.ACTIVE,
                0.88f,
                mapOf(
                    ActivityClass.REST to 0.02f,
                    ActivityClass.RESTLESS to 0.10f,
                    ActivityClass.ACTIVE to 0.88f,
                    ActivityClass.UNCERTAIN to 0.00f,
                ),
            )
        }
    }
}
