package com.example.sleeptrackersimulator.smart

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartRuleDaoTest {
    private lateinit var db: SleepTrackerDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SleepTrackerDatabase::class.java).build() }
    @After fun close() = db.close()

    @Test fun ruleCrudAndExecutionArePersistent() = runBlocking {
        val repository = RoomSmartRuleRepository(db.smartRuleDao())
        val rule = SmartRule("r1", "X > 10", true, RuleSource.ACCELEROMETER_X, RuleOperator.GREATER_THAN, 10f, null, RuleAction.VIBRATION_PULSE, 5000, 3, 1, 1)
        repository.save(rule)
        assertEquals(rule, repository.observeAll().first().single())
        repository.save(rule.copy(threshold = 12f, updatedAt = 2))
        assertEquals(12f, repository.observeAll().first().single().threshold)
        repository.record(SmartRuleExecutionEntity("e1", "r1", 3, "13.0", "VIBRATION_PULSE", "SUCCESS", null))
        repository.updateOutcome("e1", "SUCCESS", null, "PULSE_OK", "NOT_REQUESTED", "PENDING")
        assertEquals("e1", repository.observeRecentExecutions(5).first().single().executionId)
        repository.delete("r1")
        assertEquals(emptyList<SmartRule>(), repository.observeAll().first())
        val preserved = repository.observeRecentExecutions(5).first().single()
        assertEquals("e1", preserved.executionId)
        assertEquals("PENDING", preserved.cloudStatus)
    }
}
