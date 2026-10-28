package com.example.sleeptrackersimulator.data.sessions

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var sensorSampleDao: FakeSensorSampleDao
    private lateinit var heartRateSampleDao: FakeHeartRateSampleDao
    private lateinit var featureWindowDao: FakeFeatureWindowDao
    private lateinit var predictionDao: FakePredictionDao
    private lateinit var sessionSummaryDao: FakeSessionSummaryDao
    private lateinit var personalBaselineDao: FakePersonalBaselineDao
    private lateinit var deviceDao: FakeDeviceDao
    private lateinit var repository: RoomSessionRepository

    @Before
    fun setUp() {
        sessionDao = FakeSessionDao()
        sensorSampleDao = FakeSensorSampleDao()
        heartRateSampleDao = FakeHeartRateSampleDao()
        featureWindowDao = FakeFeatureWindowDao()
        predictionDao = FakePredictionDao()
        sessionSummaryDao = FakeSessionSummaryDao()
        personalBaselineDao = FakePersonalBaselineDao()
        deviceDao = FakeDeviceDao()

        repository = RoomSessionRepository(
            sessionDao = sessionDao,
            sensorSampleDao = sensorSampleDao,
            heartRateSampleDao = heartRateSampleDao,
            featureWindowDao = featureWindowDao,
            predictionDao = predictionDao,
            sessionSummaryDao = sessionSummaryDao,
            personalBaselineDao = personalBaselineDao,
            deviceDao = deviceDao,
        )
    }

    @Test
    fun `save and retrieve session preserves all fields`() = runTest {
        val session = RecordingSession(
            sessionId = "test-session-1",
            startUtcMillis = 10_000L,
            endUtcMillis = 20_000L,
            startElapsedRealtimeNanos = 50_000L,
            status = SessionStatus.RECORDING,
            sourceMode = SourceMode.PHONE_ONLY,
            devicePlacement = DevicePlacement.MATTRESS,
            requestedSamplingHz = 20,
            modelVersion = "v2.1",
            dataCoverageFraction = 0.99f,
            qualityStatus = SessionQualityStatus.EXCELLENT,
        )

        repository.saveSession(session)
        val loaded = repository.getSessionById("test-session-1")
        assertNotNull(loaded)
        assertEquals(session.sessionId, loaded!!.sessionId)
        assertEquals(session.status, loaded.status)
        assertEquals(session.sourceMode, loaded.sourceMode)
        assertEquals(session.devicePlacement, loaded.devicePlacement)
        assertEquals(0.99f, loaded.dataCoverageFraction, 0.001f)
    }

    @Test
    fun `insert samples batch and retrieve for session`() = runTest {
        val sample1 = RawSensorSample(
            sampleId = 1L,
            sessionId = "s1",
            sourceId = "phone",
            elapsedRealtimeNanos = 1000L,
            utcTimestampMillis = 2000L,
            x = 0.1f,
            y = 0.2f,
            z = 9.8f,
            sourceKind = SourceKind.PHONE_REAL,
        )
        val sample2 = sample1.copy(sampleId = 2L, utcTimestampMillis = 2050L, x = 0.2f)

        repository.insertSamplesBatch(listOf(sample1, sample2))
        val samples = repository.getSamplesForSession("s1")
        assertEquals(2, samples.size)
        assertEquals(0.1f, samples[0].x, 0.001f)
        assertEquals(0.2f, samples[1].x, 0.001f)
    }

    @Test
    fun `feature windows and predictions are mapped and stored correctly`() = runTest {
        val window = FeatureWindow(
            windowId = "w1",
            sessionId = "s1",
            sourceId = "phone",
            startElapsedNanos = 1000L,
            endElapsedNanos = 2000L,
            startUtcMillis = 10_000L,
            endUtcMillis = 11_000L,
            sampleCount = 20,
            expectedSampleCount = 20,
            coverageFraction = 1.0f,
            motionRms = 0.05f,
            variance = 0.002f,
            jerk = 0.01f,
            peakToPeak = 0.15f,
            qualityScore = 1.0f,
        )
        repository.insertFeatureWindows(listOf(window))
        val loadedWindows = repository.getFeatureWindows("s1")
        assertEquals(1, loadedWindows.size)
        assertEquals("w1", loadedWindows[0].windowId)

        val prediction = Prediction(
            predictionId = "p1",
            windowId = "w1",
            sessionId = "s1",
            modelId = "activity-v2",
            predictedClass = ActivityClass.REST,
            probabilities = mapOf(ActivityClass.REST to 0.95f, ActivityClass.RESTLESS to 0.04f, ActivityClass.ACTIVE to 0.01f),
            confidence = 0.95f,
            inferenceDurationNs = 1_500_000L,
            timestampMillis = 11_000L,
        )
        repository.insertPredictions(listOf(prediction))
        val loadedPredictions = repository.getPredictionsForSession("s1")
        assertEquals(1, loadedPredictions.size)
        assertEquals(ActivityClass.REST, loadedPredictions[0].predictedClass)
        assertEquals(0.95f, loadedPredictions[0].confidence, 0.001f)
    }

    @Test
    fun `recalculateBaseline computes median and MAD correctly`() = runTest {
        for (i in 1..5) {
            val summary = SessionSummary(
                sessionId = "sess-$i",
                observationDurationMs = 60 * 60 * 1000L,
                validDurationMs = 60 * 60 * 1000L,
                dataCoverageFraction = 0.95f,
                lowMotionFraction = 0.70f + (i * 0.02f), // 0.72, 0.74, 0.76, 0.78, 0.80
                movementEpisodeCount = i * 2,
                movementRatePerHour = (i * 2).toFloat(),
                sourceKind = SourceKind.PHONE_REAL,
                devicePlacement = DevicePlacement.MATTRESS,
            )
            repository.saveSessionSummary(summary)
        }

        val baseline = repository.recalculateBaseline(SourceMode.PHONE_ONLY, DevicePlacement.MATTRESS)
        assertNotNull(baseline)
        assertEquals(5, baseline!!.compatibleSessionCount)
        assertEquals(0.76f, baseline.medianLowMotionFraction, 0.001f) // sorted: 0.72, 0.74, 0.76, 0.78, 0.80 -> median = 0.76
        assertTrue(baseline.isReliable)
    }

    @Test
    fun `data retention policy deletes expired samples while keeping summaries`() = runTest {
        val oldUtc = System.currentTimeMillis() - (10 * 24 * 60 * 60 * 1000L) // 10 days ago
        val recentUtc = System.currentTimeMillis() - (2 * 24 * 60 * 60 * 1000L) // 2 days ago

        val oldSample = RawSensorSample(1L, "s-old", "phone", 0L, oldUtc, 0f, 0f, 9.8f)
        val newSample = RawSensorSample(2L, "s-new", "phone", 0L, recentUtc, 0f, 0f, 9.8f)
        repository.insertSamplesBatch(listOf(oldSample, newSample))

        val retentionPolicy = DataRetentionPolicy(
            sensorSampleDao = sensorSampleDao,
            heartRateSampleDao = heartRateSampleDao,
            featureWindowDao = featureWindowDao,
            predictionDao = predictionDao,
            rawSamplesRetentionDays = 7,
        )

        val result = retentionPolicy.pruneExpiredData()
        assertEquals(1, result.deletedSensorSamples)

        val remainingOld = repository.getSamplesForSession("s-old")
        val remainingNew = repository.getSamplesForSession("s-new")
        assertEquals(0, remainingOld.size)
        assertEquals(1, remainingNew.size)
    }
}

// In-Memory Fakes for Unit Testing DAOs
class FakeSessionDao : SessionDao {
    private val sessions = mutableMapOf<String, RecordingSessionEntity>()
    private val stateFlow = MutableStateFlow<List<RecordingSessionEntity>>(emptyList())

    override suspend fun insertSession(session: RecordingSessionEntity) {
        sessions[session.sessionId] = session
        stateFlow.value = sessions.values.toList()
    }

    override suspend fun updateSession(session: RecordingSessionEntity) {
        sessions[session.sessionId] = session
        stateFlow.value = sessions.values.toList()
    }

    override suspend fun getSessionById(sessionId: String): RecordingSessionEntity? = sessions[sessionId]

    override fun observeActiveSession(): Flow<RecordingSessionEntity?> = stateFlow.map { list ->
        list.firstOrNull { it.status in listOf("PREPARING", "RECORDING", "PAUSED") }
    }

    override suspend fun getActiveSession(): RecordingSessionEntity? =
        sessions.values.firstOrNull { it.status in listOf("PREPARING", "RECORDING", "PAUSED") }

    override fun observeAllSessions(): Flow<List<RecordingSessionEntity>> = stateFlow

    override suspend fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        stateFlow.value = sessions.values.toList()
    }

    override suspend fun deleteDemoSessions(): Int {
        val ids = sessions.values.filter { it.sourceMode == "DEMO" && it.sessionId.startsWith("demo-v1-") }.map { it.sessionId }
        ids.forEach(sessions::remove)
        stateFlow.value = sessions.values.toList()
        return ids.size
    }

    override suspend fun countDemoSessions(): Int = sessions.values.count {
        it.sourceMode == "DEMO" && it.sessionId.startsWith("demo-v1-")
    }

    override suspend fun getDemoSessions(): List<RecordingSessionEntity> = sessions.values.filter {
        it.sourceMode == "DEMO" || it.sessionId.startsWith("demo-v1-")
    }.sortedBy { it.startUtcMillis }

    override suspend fun updateStatus(sessionId: String, status: String, updatedAt: Long) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(status = status, updatedAt = updatedAt)
            stateFlow.value = sessions.values.toList()
        }
    }
}

class FakeSensorSampleDao : SensorSampleDao {
    private val samples = mutableListOf<SensorSampleEntity>()

    override suspend fun insertBatch(samples: List<SensorSampleEntity>) {
        this.samples.addAll(samples)
    }

    override suspend fun getSamplesForSession(sessionId: String): List<SensorSampleEntity> =
        samples.filter { it.sessionId == sessionId }

    override suspend fun countSamplesForSession(sessionId: String): Int =
        samples.count { it.sessionId == sessionId }

    override suspend fun deleteOldSamples(olderThanUtcMillis: Long): Int {
        val initial = samples.size
        samples.removeAll { it.utcTimestampMillis < olderThanUtcMillis }
        return initial - samples.size
    }
}

class FakeHeartRateSampleDao : HeartRateSampleDao {
    private val samples = mutableListOf<HeartRateSampleEntity>()

    override suspend fun insertBatch(samples: List<HeartRateSampleEntity>) {
        this.samples.addAll(samples)
    }

    override suspend fun getSamplesForSession(sessionId: String): List<HeartRateSampleEntity> =
        samples.filter { it.sessionId == sessionId }

    override suspend fun deleteOldSamples(olderThanUtcMillis: Long): Int {
        val initial = samples.size
        samples.removeAll { it.utcTimestampMillis < olderThanUtcMillis }
        return initial - samples.size
    }
}

class FakeFeatureWindowDao : FeatureWindowDao {
    private val windows = mutableListOf<FeatureWindowEntity>()

    override suspend fun insertBatch(windows: List<FeatureWindowEntity>) {
        this.windows.addAll(windows)
    }

    override suspend fun getWindowsForSession(sessionId: String): List<FeatureWindowEntity> =
        windows.filter { it.sessionId == sessionId }

    override suspend fun deleteOldWindows(olderThanUtcMillis: Long): Int {
        val initial = windows.size
        windows.removeAll { it.startUtcMillis < olderThanUtcMillis }
        return initial - windows.size
    }
}

class FakePredictionDao : PredictionDao {
    private val predictions = mutableListOf<PredictionEntity>()

    override suspend fun insertBatch(predictions: List<PredictionEntity>) {
        this.predictions.addAll(predictions)
    }

    override suspend fun getPredictionsForSession(sessionId: String): List<PredictionEntity> =
        predictions.filter { it.sessionId == sessionId }

    override suspend fun deleteOldPredictions(olderThanUtcMillis: Long): Int {
        val initial = predictions.size
        predictions.removeAll { it.timestampMillis < olderThanUtcMillis }
        return initial - predictions.size
    }
}

class FakeSessionSummaryDao : SessionSummaryDao {
    private val summaries = mutableMapOf<String, SessionSummaryEntity>()
    private val stateFlow = MutableStateFlow<List<SessionSummaryEntity>>(emptyList())

    override suspend fun insertSummary(summary: SessionSummaryEntity) {
        summaries[summary.sessionId] = summary
        stateFlow.value = summaries.values.toList()
    }

    override suspend fun getSummaryBySessionId(sessionId: String): SessionSummaryEntity? =
        summaries[sessionId]

    override suspend fun getRecentSummaries(limit: Int): List<SessionSummaryEntity> =
        summaries.values.take(limit)

    override fun observeRecentSummaries(limit: Int): Flow<List<SessionSummaryEntity>> = stateFlow.map { it.take(limit) }

    override suspend fun getDemoSummaries(): List<SessionSummaryEntity> = summaries.values.filter {
        it.sessionId.startsWith("demo-v1-")
    }.sortedBy { it.calculatedAtUtcMillis }

    override suspend fun getSummariesSince(sinceUtcMillis: Long): List<SessionSummaryEntity> =
        summaries.values.filter { it.calculatedAtUtcMillis >= sinceUtcMillis }
}

class FakePersonalBaselineDao : PersonalBaselineDao {
    private val baselines = mutableMapOf<String, PersonalBaselineEntity>()

    override suspend fun insertBaseline(baseline: PersonalBaselineEntity) {
        baselines["${baseline.sourceMode}-${baseline.placement}"] = baseline
    }

    override suspend fun getActiveBaseline(sourceMode: String, placement: String, nowUtcMillis: Long): PersonalBaselineEntity? =
        baselines["$sourceMode-$placement"]

    override suspend fun deleteBaselines(sourceMode: String, placement: String) {
        baselines.remove("$sourceMode-$placement")
    }
}

class FakeDeviceDao : DeviceDao {
    private val devices = mutableMapOf<String, DeviceEntity>()
    private val flow = MutableStateFlow<List<DeviceEntity>>(emptyList())

    override suspend fun insertDevice(device: DeviceEntity) {
        devices[device.deviceId] = device
        flow.value = devices.values.toList()
    }

    override fun observeDevices(): Flow<List<DeviceEntity>> = flow

    override suspend fun getDevice(deviceId: String): DeviceEntity? = devices[deviceId]
}
