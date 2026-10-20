package com.example.sleeptrackersimulator.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import com.example.sleeptrackersimulator.cloud.CloudOutboxDao
import com.example.sleeptrackersimulator.cloud.CloudOutboxEntity
import com.example.sleeptrackersimulator.cloud.CloudSessionEntity
import com.example.sleeptrackersimulator.smart.SmartRuleDao
import com.example.sleeptrackersimulator.smart.SmartRuleEntity
import com.example.sleeptrackersimulator.smart.SmartRuleExecutionEntity

@Database(
    entities = [SensorRecord::class, ActuatorEvent::class, CloudOutboxEntity::class, CloudSessionEntity::class, SmartRuleEntity::class, SmartRuleExecutionEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class SleepTrackerDatabase : RoomDatabase() {
    abstract fun sensorRecordDao(): SensorRecordDao
    abstract fun cloudOutboxDao(): CloudOutboxDao
    abstract fun smartRuleDao(): SmartRuleDao

    companion object {
        const val DATABASE_NAME = "sleep_tracker.db"

        @Volatile
        private var instance: SleepTrackerDatabase? = null

        fun getInstance(context: Context): SleepTrackerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SleepTrackerDatabase::class.java,
                    DATABASE_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        DiagnosticLog.event(DiagnosticTags.ROOM, "openDatabase", DiagnosticResult.SUCCESS, details = mapOf("database" to DATABASE_NAME, "version" to db.version))
                    }
                }).build().also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `actuator_events` (
                        `eventId` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `action` TEXT NOT NULL,
                        `triggerSource` TEXT NOT NULL,
                        `requestedState` INTEGER NOT NULL,
                        `result` TEXT NOT NULL,
                        `sensorRecordId` INTEGER,
                        PRIMARY KEY(`eventId`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_actuator_events_timestamp` ON `actuator_events` (`timestamp`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `cloud_outbox` (
                        `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `payloadJson` TEXT,
                        `endedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `deliveryState` TEXT NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_outbox_operationId` ON `cloud_outbox` (`operationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_outbox_sessionId_sequence` ON `cloud_outbox` (`sessionId`, `sequence`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `cloud_active_session` (
                        `singletonId` INTEGER NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `monitoringActive` INTEGER NOT NULL,
                        PRIMARY KEY(`singletonId`)
                    )""".trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `smart_rules` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `enabled` INTEGER NOT NULL,
                        `source` TEXT NOT NULL, `operator` TEXT NOT NULL, `threshold` REAL,
                        `targetClass` TEXT, `action` TEXT NOT NULL, `cooldownMillis` INTEGER NOT NULL,
                        `requiredConsecutiveMatches` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rules_enabled` ON `smart_rules` (`enabled`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `smart_rule_executions` (
                        `executionId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL,
                        `sourceValue` TEXT NOT NULL, `action` TEXT NOT NULL, `result` TEXT NOT NULL,
                        `failureReason` TEXT, PRIMARY KEY(`executionId`),
                        FOREIGN KEY(`ruleId`) REFERENCES `smart_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_ruleId` ON `smart_rule_executions` (`ruleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_timestamp` ON `smart_rule_executions` (`timestamp`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `smart_rule_executions` RENAME TO `smart_rule_executions_legacy`")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `smart_rule_executions` (
                        `executionId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL,
                        `sourceValue` TEXT NOT NULL, `action` TEXT NOT NULL, `result` TEXT NOT NULL,
                        `failureReason` TEXT, `ruleNameSnapshot` TEXT NOT NULL, `sessionId` TEXT,
                        `sensorTimestampNanos` INTEGER, `predictionId` TEXT, `actuatorStatus` TEXT NOT NULL,
                        `bleStatus` TEXT NOT NULL, `cloudStatus` TEXT NOT NULL, PRIMARY KEY(`executionId`)
                    )""".trimIndent(),
                )
                db.execSQL("""INSERT INTO `smart_rule_executions`
                    (`executionId`,`ruleId`,`timestamp`,`sourceValue`,`action`,`result`,`failureReason`,`ruleNameSnapshot`,`sessionId`,`sensorTimestampNanos`,`predictionId`,`actuatorStatus`,`bleStatus`,`cloudStatus`)
                    SELECT `executionId`,`ruleId`,`timestamp`,`sourceValue`,`action`,`result`,`failureReason`,'',NULL,NULL,NULL,'UNKNOWN','UNKNOWN','NOT_QUEUED'
                    FROM `smart_rule_executions_legacy`""")
                db.execSQL("DROP TABLE `smart_rule_executions_legacy`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_ruleId` ON `smart_rule_executions` (`ruleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_timestamp` ON `smart_rule_executions` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_cloudStatus` ON `smart_rule_executions` (`cloudStatus`)")
            }
        }
    }
}
