package com.example.sleeptrackersimulator.history

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepTrackerMigrationV7ToV8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SleepTrackerDatabase::class.java,
    )

    @Test
    fun migration7To8AddsIntentAndTombstoneTablesAndPreservesRows() {
        helper.createDatabase(NAME, 7).apply {
            execSQL("INSERT INTO sensor_records (timestamp, sensorValue, actionTriggered) VALUES (1000, 1.5, 0)")
            close()
        }

        helper.runMigrationsAndValidate(NAME, 8, true, SleepTrackerDatabase.MIGRATION_7_8).apply {
            query("SELECT timestamp FROM sensor_records").use {
                assertEquals(1, it.count)
                it.moveToFirst()
                assertEquals(1000L, it.getLong(0))
            }
            execSQL("INSERT INTO cloud_session_deletions (sessionId, ownerUid, requestedAt, completedAt) VALUES ('s1', 'u1', 1, NULL)")
            execSQL("INSERT INTO cloud_delivery_intents (operationId, type, sessionId, payloadJson, endedAt, createdAt) VALUES ('op1', 'UPLOAD_SESSION_SUMMARY', 's1', NULL, NULL, 1)")
            query("SELECT COUNT(*) FROM cloud_session_deletions").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            query("SELECT COUNT(*) FROM cloud_delivery_intents").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            close()
        }
    }

    @Test
    fun everyPublishedSchemaMigratesTo8AndReopens() {
        val migrations = arrayOf(
            SleepTrackerDatabase.MIGRATION_1_2,
            SleepTrackerDatabase.MIGRATION_2_3,
            SleepTrackerDatabase.MIGRATION_3_4,
            SleepTrackerDatabase.MIGRATION_4_5,
            SleepTrackerDatabase.MIGRATION_5_6,
            SleepTrackerDatabase.MIGRATION_6_7,
            SleepTrackerDatabase.MIGRATION_7_8,
        )
        (1..7).forEach { version ->
            val name = "migration-$version-v8.db"
            helper.createDatabase(name, version).close()
            helper.runMigrationsAndValidate(name, 8, true, *migrations.drop(version - 1).toTypedArray()).close()
            helper.runMigrationsAndValidate(name, 8, true).close()
        }
    }

    companion object {
        private const val NAME = "migration-v7-v8.db"
    }
}
