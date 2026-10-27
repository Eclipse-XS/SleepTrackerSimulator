package com.example.sleeptrackersimulator.dataset

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.example.sleeptrackersimulator.core.model.SensorSample
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

enum class ExperimentType(val label: String) {
    STATIC_BENCH("Static Bench Baseline"),
    READING_IN_BED("Awake / Reading in Bed"),
    CONTROLLED_REST("Controlled Stationary Rest"),
    PERIODIC_MOVEMENT("Periodic Movement / Tossing"),
    OUT_OF_BED_WALK("Out of Bed / Walking Activity"),
}

enum class DevicePlacement(val label: String) {
    MATTRESS("Mattress (Beside Pillow)"),
    NIGHTSTAND("Rigid Nightstand"),
    BODY_CHEST("Body Placement (Chest)"),
    BODY_WRIST("Body Placement (Wrist)"),
}

enum class GroundTruthActivity(val label: String) {
    RESTING("RESTING"),
    READING("READING"),
    OUT_OF_BED("OUT_OF_BED"),
    WALKING("WALKING"),
}

data class DatasetSample(
    val timestampUtcMs: Long,
    val elapsedRealtimeNanos: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val dynamicMagnitude: Float,
    val isValid: Boolean,
    val rejectionReason: String?,
    val groundTruthLabel: String,
    val predictedMotionClass: String,
)

data class DatasetSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val experimentType: ExperimentType,
    val placement: DevicePlacement,
    val groundTruthActivity: GroundTruthActivity,
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val startTimeUtcMs: Long,
    val endTimeUtcMs: Long,
    val samples: List<DatasetSample>,
) {
    val totalSamples: Int get() = samples.size
    val validCount: Int get() = samples.count { it.isValid }
    val rejectedCount: Int get() = samples.count { !it.isValid }

    val durationSeconds: Double
        get() = ((endTimeUtcMs - startTimeUtcMs).coerceAtLeast(1L)).toDouble() / 1000.0

    val actualSamplingRateHz: Double
        get() = if (durationSeconds > 0) validCount / durationSeconds else 0.0
}

object DatasetCsvExporter {

    private const val HEADER_PREFIX = "#"
    private const val CSV_COLUMNS = "timestamp_utc_ms,elapsed_realtime_ns,accel_x,accel_y,accel_z,dynamic_magnitude,is_valid,ground_truth_label,predicted_motion_class"

    fun generateCsv(session: DatasetSession): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val sb = StringBuilder()

        sb.appendLine("$HEADER_PREFIX SleepSense Ground-Truth Accelerometry Dataset v2.0")
        sb.appendLine("$HEADER_PREFIX SessionId: ${session.sessionId}")
        sb.appendLine("$HEADER_PREFIX ExperimentType: ${session.experimentType.name}")
        sb.appendLine("$HEADER_PREFIX Placement: ${session.placement.name}")
        sb.appendLine("$HEADER_PREFIX GroundTruthActivity: ${session.groundTruthActivity.name}")
        sb.appendLine("$HEADER_PREFIX DeviceModel: ${session.deviceModel}")
        sb.appendLine("$HEADER_PREFIX StartTimeUtc: ${isoFormat.format(Date(session.startTimeUtcMs))}")
        sb.appendLine("$HEADER_PREFIX EndTimeUtc: ${isoFormat.format(Date(session.endTimeUtcMs))}")
        sb.appendLine("$HEADER_PREFIX DurationSeconds: ${String.format(Locale.US, "%.3f", session.durationSeconds)}")
        sb.appendLine("$HEADER_PREFIX TotalSamples: ${session.totalSamples}")
        sb.appendLine("$HEADER_PREFIX ValidSamples: ${session.validCount}")
        sb.appendLine("$HEADER_PREFIX RejectedSamples: ${session.rejectedCount}")
        sb.appendLine("$HEADER_PREFIX ActualSamplingRateHz: ${String.format(Locale.US, "%.2f", session.actualSamplingRateHz)}")
        sb.appendLine(CSV_COLUMNS)

        for (s in session.samples) {
            sb.append(s.timestampUtcMs).append(',')
                .append(s.elapsedRealtimeNanos).append(',')
                .append(String.format(Locale.US, "%.5f", s.x)).append(',')
                .append(String.format(Locale.US, "%.5f", s.y)).append(',')
                .append(String.format(Locale.US, "%.5f", s.z)).append(',')
                .append(String.format(Locale.US, "%.5f", s.dynamicMagnitude)).append(',')
                .append(s.isValid).append(',')
                .append(s.groundTruthLabel).append(',')
                .append(s.predictedMotionClass).append('\n')
        }

        return sb.toString()
    }

    fun parseCsv(csvContent: String): DatasetSession {
        var sessionId = ""
        var experimentType = ExperimentType.CONTROLLED_REST
        var placement = DevicePlacement.MATTRESS
        var groundTruthActivity = GroundTruthActivity.RESTING
        var deviceModel = "Unknown"
        var startTimeUtcMs = 0L
        var endTimeUtcMs = 0L

        val samples = mutableListOf<DatasetSample>()

        StringReader(csvContent).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith(HEADER_PREFIX)) {
                    val metaLine = trimmed.removePrefix(HEADER_PREFIX).trim()
                    val colonIndex = metaLine.indexOf(':')
                    if (colonIndex != -1) {
                        val key = metaLine.substring(0, colonIndex).trim()
                        val value = metaLine.substring(colonIndex + 1).trim()
                        when (key) {
                            "SessionId" -> sessionId = value
                            "ExperimentType" -> runCatching { experimentType = ExperimentType.valueOf(value) }
                            "Placement" -> runCatching { placement = DevicePlacement.valueOf(value) }
                            "GroundTruthActivity" -> runCatching { groundTruthActivity = GroundTruthActivity.valueOf(value) }
                            "DeviceModel" -> deviceModel = value
                        }
                    }
                    continue
                }

                if (trimmed.startsWith("timestamp_utc_ms")) {
                    // Header row
                    continue
                }

                val tokens = trimmed.split(',')
                if (tokens.size >= 9) {
                    val utcMs = tokens[0].toLongOrNull() ?: 0L
                    val elapsedNs = tokens[1].toLongOrNull() ?: 0L
                    val x = tokens[2].toFloatOrNull() ?: 0f
                    val y = tokens[3].toFloatOrNull() ?: 0f
                    val z = tokens[4].toFloatOrNull() ?: 0f
                    val mag = tokens[5].toFloatOrNull() ?: 0f
                    val isValid = tokens[6].toBoolean()
                    val gtLabel = tokens[7]
                    val predClass = tokens[8]

                    if (startTimeUtcMs == 0L) startTimeUtcMs = utcMs
                    endTimeUtcMs = utcMs

                    samples.add(
                        DatasetSample(
                            timestampUtcMs = utcMs,
                            elapsedRealtimeNanos = elapsedNs,
                            x = x,
                            y = y,
                            z = z,
                            dynamicMagnitude = mag,
                            isValid = isValid,
                            rejectionReason = if (!isValid) "ValidationFailure" else null,
                            groundTruthLabel = gtLabel,
                            predictedMotionClass = predClass,
                        )
                    )
                }
            }
        }

        return DatasetSession(
            sessionId = sessionId.ifBlank { UUID.randomUUID().toString() },
            experimentType = experimentType,
            placement = placement,
            groundTruthActivity = groundTruthActivity,
            deviceModel = deviceModel,
            startTimeUtcMs = startTimeUtcMs,
            endTimeUtcMs = endTimeUtcMs,
            samples = samples,
        )
    }

    fun exportToInternalFile(context: Context, session: DatasetSession): File {
        val dir = File(context.getExternalFilesDir(null), "datasets").apply { mkdirs() }
        val filename = "sleepsense_${session.experimentType.name.lowercase()}_${session.startTimeUtcMs}.csv"
        val target = File(dir, filename)
        target.writeText(generateCsv(session))
        return target
    }
}

class DatasetRecordingController {

    private val samples = mutableListOf<DatasetSample>()
    private var isRecording = false
    private var startUtcMs: Long = 0L
    private var experimentType: ExperimentType = ExperimentType.CONTROLLED_REST
    private var placement: DevicePlacement = DevicePlacement.MATTRESS
    private var currentGroundTruth: GroundTruthActivity = GroundTruthActivity.RESTING

    val isRunning: Boolean get() = isRecording
    val sampleCount: Int get() = samples.size
    val validSampleCount: Int get() = samples.count { it.isValid }
    val rejectedSampleCount: Int get() = samples.count { !it.isValid }

    fun start(
        experiment: ExperimentType,
        devicePlacement: DevicePlacement,
        groundTruth: GroundTruthActivity,
    ) {
        samples.clear()
        experimentType = experiment
        placement = devicePlacement
        currentGroundTruth = groundTruth
        startUtcMs = System.currentTimeMillis()
        isRecording = true
    }

    fun updateGroundTruth(newGroundTruth: GroundTruthActivity) {
        currentGroundTruth = newGroundTruth
    }

    fun processSample(sample: SensorSample, predictedClass: String = "REST"): DatasetSample {
        val utcNow = System.currentTimeMillis()
        val elapsedNs = sample.timestampNanos

        // Validation against physical bounds
        val isFinite = !sample.x.isNaN() && !sample.x.isInfinite() &&
            !sample.y.isNaN() && !sample.y.isInfinite() &&
            !sample.z.isNaN() && !sample.z.isInfinite()

        val isPhysicallyPlausible = isFinite &&
            abs(sample.x) <= 50f && abs(sample.y) <= 50f && abs(sample.z) <= 50f

        val isValid = isPhysicallyPlausible
        val rejectionReason = when {
            !isFinite -> "NON_FINITE_VALUE"
            !isPhysicallyPlausible -> "OUT_OF_RANGE_EXCESSIVE_G"
            else -> null
        }

        // Dynamic acceleration |sqrt(x^2 + y^2 + z^2) - 9.80665|
        val norm = sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z)
        val dynamicMag = abs(norm - 9.80665f)

        val entry = DatasetSample(
            timestampUtcMs = utcNow,
            elapsedRealtimeNanos = elapsedNs,
            x = sample.x,
            y = sample.y,
            z = sample.z,
            dynamicMagnitude = dynamicMag,
            isValid = isValid,
            rejectionReason = rejectionReason,
            groundTruthLabel = currentGroundTruth.name,
            predictedMotionClass = predictedClass,
        )

        if (isRecording) {
            samples.add(entry)
        }

        return entry
    }

    fun stop(): DatasetSession {
        isRecording = false
        val endUtcMs = System.currentTimeMillis()
        return DatasetSession(
            experimentType = experimentType,
            placement = placement,
            groundTruthActivity = currentGroundTruth,
            startTimeUtcMs = startUtcMs,
            endTimeUtcMs = endUtcMs,
            samples = samples.toList(),
        )
    }
}
