package com.example.sleeptrackersimulator.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensorRecordDaoTest {
    private lateinit var database: SleepTrackerDatabase
    private lateinit var dao: SensorRecordDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SleepTrackerDatabase::class.java,
        ).build()
        dao = database.sensorRecordDao()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun recentQueryLimitsNewestRecordsAndReturnsChronologicalOrder() = runBlocking {
        dao.insert(SensorRecord(timestamp = 1_000, sensorValue = .1f, actionTriggered = false))
        dao.insert(SensorRecord(timestamp = 3_000, sensorValue = .3f, actionTriggered = true))
        dao.insert(SensorRecord(timestamp = 2_000, sensorValue = .2f, actionTriggered = false))

        val records = dao.observeRecent(sinceInclusive = 1, limit = 2).first()

        assertEquals(listOf(2_000L, 3_000L), records.map { it.timestamp })
        assertEquals(3, dao.observeCount(1).first())
        assertEquals(1, dao.observeActionCount(1).first())
    }

    @Test
    fun intervalQueryAndRetentionUseTimestampBoundaries() = runBlocking {
        dao.insert(SensorRecord(timestamp = 1_000, sensorValue = .1f, actionTriggered = false))
        dao.insert(SensorRecord(timestamp = 2_000, sensorValue = .2f, actionTriggered = false))
        dao.insert(SensorRecord(timestamp = 3_000, sensorValue = .3f, actionTriggered = false))

        assertEquals(listOf(2_000L, 3_000L), dao.recordsBetween(2_000, 3_000).map { it.timestamp })
        assertEquals(1, dao.deleteOlderThan(2_000))
        assertEquals(2, dao.observeCount(1).first())
    }
}
