package com.example.sleeptrackersimulator.data.sessions

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRetentionPolicyTest {

    @Test
    fun testPruneExpiredDataDeletesPastCutoff() = runTest {
        val now = 1_700_000_000_000L
        val eightDaysAgo = now - (8 * 24 * 60 * 60 * 1000L)
        val twoDaysAgo = now - (2 * 24 * 60 * 60 * 1000L)

        val sensorDao = FakeSensorSampleDao()
        sensorDao.insertBatch(listOf(
            SensorSampleEntity(sampleId = 1, sessionId = "s1", sourceId = "accel", elapsedRealtimeNanos = 1, utcTimestampMillis = eightDaysAgo, x = 0f, y = 0f, z = 9.8f, sensorAccuracy = 3, sourceKind = "PHONE_REAL"),
            SensorSampleEntity(sampleId = 2, sessionId = "s2", sourceId = "accel", elapsedRealtimeNanos = 2, utcTimestampMillis = twoDaysAgo, x = 0f, y = 0f, z = 9.8f, sensorAccuracy = 3, sourceKind = "PHONE_REAL"),
        ))

        val hrDao = FakeHeartRateSampleDao()
        hrDao.insertBatch(listOf(
            HeartRateSampleEntity(sampleId = "hr1", sessionId = "s1", deviceId = "ble", bpm = 60, utcTimestampMillis = eightDaysAgo, timingUncertaintyMillis = 10, sourceKind = "BLE_REAL"),
            HeartRateSampleEntity(sampleId = "hr2", sessionId = "s2", deviceId = "ble", bpm = 65, utcTimestampMillis = twoDaysAgo, timingUncertaintyMillis = 10, sourceKind = "BLE_REAL"),
        ))

        val windowDao = FakeFeatureWindowDao()
        val thirtyFiveDaysAgo = now - (35 * 24 * 60 * 60 * 1000L)
        windowDao.insertBatch(listOf(
            FeatureWindowEntity(windowId = "w1", sessionId = "s1", sourceId = "accel", startElapsedNanos = 1, endElapsedNanos = 2, startUtcMillis = thirtyFiveDaysAgo, endUtcMillis = thirtyFiveDaysAgo + 1000, sampleCount = 20, expectedSampleCount = 20, coverageFraction = 1f, motionRms = 0.1f, variance = 0.01f, jerk = 0.1f, peakToPeak = 0.2f, qualityScore = 1f, preprocessingVersion = 1),
            FeatureWindowEntity(windowId = "w2", sessionId = "s2", sourceId = "accel", startElapsedNanos = 3, endElapsedNanos = 4, startUtcMillis = twoDaysAgo, endUtcMillis = twoDaysAgo + 1000, sampleCount = 20, expectedSampleCount = 20, coverageFraction = 1f, motionRms = 0.1f, variance = 0.01f, jerk = 0.1f, peakToPeak = 0.2f, qualityScore = 1f, preprocessingVersion = 1),
        ))

        val predDao = FakePredictionDao()
        predDao.insertBatch(listOf(
            PredictionEntity(predictionId = "p1", windowId = "w1", sessionId = "s1", modelId = "m1", predictedClass = "REST", probabilitiesJson = "{}", confidence = 0.9f, inferenceDurationNs = 100, timestampMillis = thirtyFiveDaysAgo),
            PredictionEntity(predictionId = "p2", windowId = "w2", sessionId = "s2", modelId = "m1", predictedClass = "REST", probabilitiesJson = "{}", confidence = 0.9f, inferenceDurationNs = 100, timestampMillis = twoDaysAgo),
        ))

        val policy = DataRetentionPolicy(
            sensorSampleDao = sensorDao,
            heartRateSampleDao = hrDao,
            featureWindowDao = windowDao,
            predictionDao = predDao,
            sessionDao = null,
            rawSamplesRetentionDays = 7,
            featuresRetentionDays = 30,
            predictionsRetentionDays = 30,
        )

        val result = policy.pruneExpiredData(activeSessionId = null, now = now)

        assertEquals(1, result.deletedSensorSamples)
        assertEquals(1, result.deletedHeartRateSamples)
        assertEquals(1, result.deletedFeatureWindows)
        assertEquals(1, result.deletedPredictions)

        // Verify remaining samples
        assertEquals(1, sensorDao.getSamplesForSession("s2").size)
        assertEquals(0, sensorDao.getSamplesForSession("s1").size)
        assertEquals(1, hrDao.getSamplesForSession("s2").size)
        assertEquals(0, hrDao.getSamplesForSession("s1").size)
    }

    @Test
    fun testActiveSessionIsNeverPruned() = runTest {
        val now = 1_700_000_000_000L
        val eightDaysAgo = now - (8 * 24 * 60 * 60 * 1000L)

        val sensorDao = FakeSensorSampleDao()
        // Both samples are 8 days old, but s_active is currently active
        sensorDao.insertBatch(listOf(
            SensorSampleEntity(sampleId = 1, sessionId = "s_old", sourceId = "accel", elapsedRealtimeNanos = 1, utcTimestampMillis = eightDaysAgo, x = 0f, y = 0f, z = 9.8f, sensorAccuracy = 3, sourceKind = "PHONE_REAL"),
            SensorSampleEntity(sampleId = 2, sessionId = "s_active", sourceId = "accel", elapsedRealtimeNanos = 2, utcTimestampMillis = eightDaysAgo, x = 0f, y = 0f, z = 9.8f, sensorAccuracy = 3, sourceKind = "PHONE_REAL"),
        ))

        val policy = DataRetentionPolicy(
            sensorSampleDao = sensorDao,
            heartRateSampleDao = FakeHeartRateSampleDao(),
            featureWindowDao = FakeFeatureWindowDao(),
            predictionDao = FakePredictionDao(),
            sessionDao = null,
        )

        val result = policy.pruneExpiredData(activeSessionId = "s_active", now = now)

        assertEquals(1, result.deletedSensorSamples)
        assertEquals(0, sensorDao.getSamplesForSession("s_old").size)
        assertEquals(1, sensorDao.getSamplesForSession("s_active").size)
    }
}
