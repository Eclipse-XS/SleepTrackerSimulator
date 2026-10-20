package com.example.sleeptrackersimulator.history

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepTrackerMigrationV5ToV6Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SleepTrackerDatabase::class.java,
    )

    @Test fun migration5To6PreservesRowsAndAddsGatedDeliveryFields() {
        helper.createDatabase(NAME, 5).apply {
            execSQL("""INSERT INTO smart_rule_executions
                (executionId,ruleId,timestamp,sourceValue,action,result,failureReason,ruleNameSnapshot,sessionId,sensorTimestampNanos,predictionId,actuatorStatus,bleStatus,cloudStatus)
                VALUES ('e1','r1',10,'ACTIVE','MOCK_BLE_ALERT','SUCCESS',NULL,'Rule 1','s1',9,'p1','NOT_REQUESTED','ACK','PENDING')""")
            execSQL("""INSERT INTO cloud_outbox
                (operationId,type,sessionId,payloadJson,endedAt,createdAt,retryCount,lastError,deliveryState)
                VALUES ('rule-e1','UPLOAD_RULE_EVENT','s1','{}',NULL,10,0,NULL,'PENDING')""")
            close()
        }

        helper.runMigrationsAndValidate(NAME, 6, true, SleepTrackerDatabase.MIGRATION_5_6).apply {
            query("SELECT executionId, triggerSource, mlClass, mlConfidence FROM smart_rule_executions").use {
                it.moveToFirst()
                assertEquals("e1", it.getString(0))
                assertEquals("", it.getString(1))
                assertEquals(true, it.isNull(2))
                assertEquals(true, it.isNull(3))
            }
            query("SELECT readyForDelivery FROM cloud_outbox WHERE operationId='rule-e1'").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            close()
        }
    }

    @Test fun everyPublishedSchemaMigratesTo6AndReopens() {
        val migrations = arrayOf(
            SleepTrackerDatabase.MIGRATION_1_2,
            SleepTrackerDatabase.MIGRATION_2_3,
            SleepTrackerDatabase.MIGRATION_3_4,
            SleepTrackerDatabase.MIGRATION_4_5,
            SleepTrackerDatabase.MIGRATION_5_6,
        )
        (1..5).forEach { version ->
            val name = "migration-$version-v6.db"
            helper.createDatabase(name, version).close()
            helper.runMigrationsAndValidate(name, 6, true, *migrations.drop(version - 1).toTypedArray()).close()
            helper.runMigrationsAndValidate(name, 6, true).close()
        }
    }

    companion object { private const val NAME = "migration-v5-v6.db" }
}
