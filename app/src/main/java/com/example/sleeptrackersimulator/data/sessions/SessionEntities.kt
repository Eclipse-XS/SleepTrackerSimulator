package com.example.sleeptrackersimulator.data.sessions

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.FeatureWindow
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.PersonalBaseline
import com.example.sleeptrackersimulator.core.model.Prediction
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionQualityStatus
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(
    tableName = "recording_sessions",
    indices = [
        Index("startUtcMillis"),
        Index("status"),
    ],
)
data class RecordingSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long?,
    val startElapsedRealtimeNanos: Long,
    val status: String,
    val sourceMode: String,
    val devicePlacement: String,
    val requestedSamplingHz: Int,
    val modelVersion: String?,
    val dataCoverageFraction: Float,
    val qualityStatus: String,
    val interruptionReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain(): RecordingSession = RecordingSession(
        sessionId = sessionId,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        startElapsedRealtimeNanos = startElapsedRealtimeNanos,
        status = SessionStatus.valueOf(status),
        sourceMode = SourceMode.valueOf(sourceMode),
        devicePlacement = DevicePlacement.fromString(devicePlacement),
        requestedSamplingHz = requestedSamplingHz,
        modelVersion = modelVersion,
        dataCoverageFraction = dataCoverageFraction,
        qualityStatus = SessionQualityStatus.valueOf(qualityStatus),
        interruptionReason = interruptionReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(session: RecordingSession): RecordingSessionEntity = RecordingSessionEntity(
            sessionId = session.sessionId,
            startUtcMillis = session.startUtcMillis,
            endUtcMillis = session.endUtcMillis,
            startElapsedRealtimeNanos = session.startElapsedRealtimeNanos,
            status = session.status.name,
            sourceMode = session.sourceMode.name,
            devicePlacement = session.devicePlacement.name,
            requestedSamplingHz = session.requestedSamplingHz,
            modelVersion = session.modelVersion,
            dataCoverageFraction = session.dataCoverageFraction,
            qualityStatus = session.qualityStatus.name,
            interruptionReason = session.interruptionReason,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
        )
    }
}

@Entity(
    tableName = "sensor_samples",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId", "utcTimestampMillis"),
    ],
)
data class SensorSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val sampleId: Long = 0,
    val sessionId: String,
    val sourceId: String,
    val elapsedRealtimeNanos: Long,
    val utcTimestampMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val sensorAccuracy: Int,
    val sourceKind: String,
) {
    fun toDomain(): RawSensorSample = RawSensorSample(
        sampleId = sampleId,
        sessionId = sessionId,
        sourceId = sourceId,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        utcTimestampMillis = utcTimestampMillis,
        x = x,
        y = y,
        z = z,
        sensorAccuracy = sensorAccuracy,
        sourceKind = SourceKind.valueOf(sourceKind),
    )

    companion object {
        fun fromDomain(sample: RawSensorSample): SensorSampleEntity = SensorSampleEntity(
            sampleId = sample.sampleId,
            sessionId = sample.sessionId,
            sourceId = sample.sourceId,
            elapsedRealtimeNanos = sample.elapsedRealtimeNanos,
            utcTimestampMillis = sample.utcTimestampMillis,
            x = sample.x,
            y = sample.y,
            z = sample.z,
            sensorAccuracy = sample.sensorAccuracy,
            sourceKind = sample.sourceKind.name,
        )
    }
}

@Entity(
    tableName = "heart_rate_samples",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId", "utcTimestampMillis"),
    ],
)
data class HeartRateSampleEntity(
    @PrimaryKey
    val sampleId: String,
    val sessionId: String,
    val deviceId: String,
    val bpm: Int,
    val utcTimestampMillis: Long,
    val timingUncertaintyMillis: Long,
    val sourceKind: String,
) {
    fun toDomain(): HeartRateSample = HeartRateSample(
        sampleId = sampleId,
        sessionId = sessionId,
        deviceId = deviceId,
        bpm = bpm,
        utcTimestampMillis = utcTimestampMillis,
        timingUncertaintyMillis = timingUncertaintyMillis,
        sourceKind = SourceKind.valueOf(sourceKind),
    )

    companion object {
        fun fromDomain(sample: HeartRateSample): HeartRateSampleEntity = HeartRateSampleEntity(
            sampleId = sample.sampleId,
            sessionId = sample.sessionId,
            deviceId = sample.deviceId,
            bpm = sample.bpm,
            utcTimestampMillis = sample.utcTimestampMillis,
            timingUncertaintyMillis = sample.timingUncertaintyMillis,
            sourceKind = sample.sourceKind.name,
        )
    }
}

@Entity(
    tableName = "feature_windows",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId", "startUtcMillis"),
    ],
)
data class FeatureWindowEntity(
    @PrimaryKey
    val windowId: String,
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
    val preprocessingVersion: Int,
) {
    fun toDomain(): FeatureWindow = FeatureWindow(
        windowId = windowId,
        sessionId = sessionId,
        sourceId = sourceId,
        startElapsedNanos = startElapsedNanos,
        endElapsedNanos = endElapsedNanos,
        startUtcMillis = startUtcMillis,
        endUtcMillis = endUtcMillis,
        sampleCount = sampleCount,
        expectedSampleCount = expectedSampleCount,
        coverageFraction = coverageFraction,
        motionRms = motionRms,
        variance = variance,
        jerk = jerk,
        peakToPeak = peakToPeak,
        qualityScore = qualityScore,
        preprocessingVersion = preprocessingVersion,
    )

    companion object {
        fun fromDomain(w: FeatureWindow): FeatureWindowEntity = FeatureWindowEntity(
            windowId = w.windowId,
            sessionId = w.sessionId,
            sourceId = w.sourceId,
            startElapsedNanos = w.startElapsedNanos,
            endElapsedNanos = w.endElapsedNanos,
            startUtcMillis = w.startUtcMillis,
            endUtcMillis = w.endUtcMillis,
            sampleCount = w.sampleCount,
            expectedSampleCount = w.expectedSampleCount,
            coverageFraction = w.coverageFraction,
            motionRms = w.motionRms,
            variance = w.variance,
            jerk = w.jerk,
            peakToPeak = w.peakToPeak,
            qualityScore = w.qualityScore,
            preprocessingVersion = w.preprocessingVersion,
        )
    }
}

@Entity(
    tableName = "predictions",
    foreignKeys = [
        ForeignKey(
            entity = FeatureWindowEntity::class,
            parentColumns = ["windowId"],
            childColumns = ["windowId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId", "timestampMillis"),
        Index("windowId"),
    ],
)
data class PredictionEntity(
    @PrimaryKey
    val predictionId: String,
    val windowId: String,
    val sessionId: String,
    val modelId: String,
    val predictedClass: String,
    val probabilitiesJson: String,
    val confidence: Float,
    val inferenceDurationNs: Long,
    val timestampMillis: Long,
) {
    fun toDomain(gson: Gson = Gson()): Prediction {
        val mapType = object : TypeToken<Map<String, Float>>() {}.type
        val rawMap: Map<String, Float> = gson.fromJson(probabilitiesJson, mapType) ?: emptyMap()
        val probs = rawMap.mapKeys { ActivityClass.fromString(it.key) }

        return Prediction(
            predictionId = predictionId,
            windowId = windowId,
            sessionId = sessionId,
            modelId = modelId,
            predictedClass = ActivityClass.fromString(predictedClass),
            probabilities = probs,
            confidence = confidence,
            inferenceDurationNs = inferenceDurationNs,
            timestampMillis = timestampMillis,
        )
    }

    companion object {
        fun fromDomain(p: Prediction, gson: Gson = Gson()): PredictionEntity {
            val probsMap = p.probabilities.mapKeys { it.key.name }
            return PredictionEntity(
                predictionId = p.predictionId,
                windowId = p.windowId,
                sessionId = p.sessionId,
                modelId = p.modelId,
                predictedClass = p.predictedClass.name,
                probabilitiesJson = gson.toJson(probsMap),
                confidence = p.confidence,
                inferenceDurationNs = p.inferenceDurationNs,
                timestampMillis = p.timestampMillis,
            )
        }
    }
}

@Entity(
    tableName = "session_summaries",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionSummaryEntity(
    @PrimaryKey
    val sessionId: String,
    val observationDurationMs: Long,
    val validDurationMs: Long,
    val dataCoverageFraction: Float,
    val lowMotionFraction: Float,
    val movementEpisodeCount: Int,
    val movementRatePerHour: Float,
    val averageHeartRateBpm: Int?,
    val sourceKind: String,
    val devicePlacement: String,
    val modelVersion: String,
    val calculatedAtUtcMillis: Long,
) {
    fun toDomain(): SessionSummary = SessionSummary(
        sessionId = sessionId,
        observationDurationMs = observationDurationMs,
        validDurationMs = validDurationMs,
        dataCoverageFraction = dataCoverageFraction,
        lowMotionFraction = lowMotionFraction,
        movementEpisodeCount = movementEpisodeCount,
        movementRatePerHour = movementRatePerHour,
        averageHeartRateBpm = averageHeartRateBpm,
        sourceKind = SourceKind.valueOf(sourceKind),
        devicePlacement = DevicePlacement.fromString(devicePlacement),
        modelVersion = modelVersion,
        calculatedAtUtcMillis = calculatedAtUtcMillis,
    )

    companion object {
        fun fromDomain(s: SessionSummary): SessionSummaryEntity = SessionSummaryEntity(
            sessionId = s.sessionId,
            observationDurationMs = s.observationDurationMs,
            validDurationMs = s.validDurationMs,
            dataCoverageFraction = s.dataCoverageFraction,
            lowMotionFraction = s.lowMotionFraction,
            movementEpisodeCount = s.movementEpisodeCount,
            movementRatePerHour = s.movementRatePerHour,
            averageHeartRateBpm = s.averageHeartRateBpm,
            sourceKind = s.sourceKind.name,
            devicePlacement = s.devicePlacement.name,
            modelVersion = s.modelVersion,
            calculatedAtUtcMillis = s.calculatedAtUtcMillis,
        )
    }
}

@Entity(
    tableName = "personal_baselines",
    indices = [
        Index("sourceMode", "placement"),
    ],
)
data class PersonalBaselineEntity(
    @PrimaryKey
    val baselineId: String,
    val sourceMode: String,
    val placement: String,
    val compatibleSessionCount: Int,
    val medianLowMotionFraction: Float,
    val madLowMotionFraction: Float,
    val medianMovementRatePerHour: Float,
    val validFromUtcMillis: Long,
    val validUntilUtcMillis: Long?,
    val baselineVersion: Int,
) {
    fun toDomain(): PersonalBaseline = PersonalBaseline(
        baselineId = baselineId,
        sourceMode = SourceMode.valueOf(sourceMode),
        placement = DevicePlacement.fromString(placement),
        compatibleSessionCount = compatibleSessionCount,
        medianLowMotionFraction = medianLowMotionFraction,
        madLowMotionFraction = madLowMotionFraction,
        medianMovementRatePerHour = medianMovementRatePerHour,
        validFromUtcMillis = validFromUtcMillis,
        validUntilUtcMillis = validUntilUtcMillis,
        baselineVersion = baselineVersion,
    )

    companion object {
        fun fromDomain(b: PersonalBaseline): PersonalBaselineEntity = PersonalBaselineEntity(
            baselineId = b.baselineId,
            sourceMode = b.sourceMode.name,
            placement = b.placement.name,
            compatibleSessionCount = b.compatibleSessionCount,
            medianLowMotionFraction = b.medianLowMotionFraction,
            madLowMotionFraction = b.madLowMotionFraction,
            medianMovementRatePerHour = b.medianMovementRatePerHour,
            validFromUtcMillis = b.validFromUtcMillis,
            validUntilUtcMillis = b.validUntilUtcMillis,
            baselineVersion = b.baselineVersion,
        )
    }
}

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    val deviceId: String,
    val displayName: String,
    val sourceKind: String,
    val capabilitiesCsv: String,
    val batteryLevel: Int?,
    val lastSeenUtcMillis: Long,
)
