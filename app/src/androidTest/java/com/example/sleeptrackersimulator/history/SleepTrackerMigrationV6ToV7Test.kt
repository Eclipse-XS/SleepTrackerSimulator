package com.example.sleeptrackersimulator.history

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepTrackerMigrationV6ToV7Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SleepTrackerDatabase::class.java,
    )

    @Test
    fun migration6To7CreatesNewTablesAndPreservesLegacyRows() {
        helper.createDatabase(NAME, 6).apply {
            execSQL("INSERT INTO sensor_records (timestamp, sensorType, sensorValue, actionTriggered) VALUES (1000, 'ACCELEROMETER', 1.5, 0)")
            execSQL("""INSERT INTO smart_rule_executions
                (executionId,ruleId,timestamp,sourceValue,action,result,failureReason,ruleNameSnapshot,sessionId,sensorTimestampNanos,predictionId,actuatorStatus,bleStatus,cloudStatus,triggerSource,mlClass,mlConfidence)
                VALUES ('e1','r1',10,'ACTIVE','MOCK_BLE_ALERT','SUCCESS',NULL,'Rule 1','s1',9,'p1','NOT_REQUESTED','ACK','PENDING','ACCEL','ACTIVE',0.95)""")
            close()
        }

        helper.runMigrationsAndValidate(NAME, 7, true, SleepTrackerDatabase.MIGRATION_6_7).apply {
            query("SELECT timestamp, sensorValue FROM sensor_records").use {
                it.moveToFirst()
                assertEquals(1000L, it.getLong(0))
                assertEquals(1.5, it.getDouble(1), 0.001)
            }

            // Verify new tables exist and are functional
            execSQL("""INSERT INTO recording_sessions
                (sessionId, startUtcMillis, endUtcMillis, startElapsedRealtimeNanos, status, sourceMode, devicePlacement, requestedSamplingHz, modelVersion, dataCoverageFraction, qualityStatus, interruptionReason, createdAt, updatedAt)
                VALUES ('sess-1', 1000, 5000, 10000, 'COMPLETED', 'PHONE_ONLY', 'MATTRESS', 20, 'v2.1', 0.98, 'EXCELLENT', NULL, 1000, 5000)""")

            query("SELECT sessionId, status, dataCoverageFraction FROM recording_sessions WHERE sessionId='sess-1'").use {
                it.moveToFirst()
                assertEquals("sess-1", it.getString(0))
                assertEquals("COMPLETED", it.getString(1))
                assertEquals(0.98, it.getDouble(2), 0.01)
            }
            close()
        }
    }

    @Test
    fun everyPublishedSchemaMigratesTo7AndReopens() {
        val migrations = arrayOf(
            SleepTrackerDatabase.MIGRATION_1_2,
            SleepTrackerDatabase.MIGRATION_2_3,
            SleepTrackerDatabase.MIGRATION_3_4,
            SleepTrackerDatabase.MIGRATION_4_5,
            SleepTrackerDatabase.MIGRATION_5_6,
            SleepTrackerDatabase.MIGRATION_6_7,
        )
        (1..6).forEach { version ->
            val name = "migration-$version-v7.db"
            helper.createDatabase(name, version).close()
            helper.runMigrationsAndValidate(name, 7, true, *migrations.drop(version - 1).toTypedArray()).close()
            helper.runMigrationsAndValidate(name, 7, true).close()
        }
    }

    companion object {
        private const val NAME = "migration-v6-v7.db"
    }
}
