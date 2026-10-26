package com.example.sleeptrackersimulator.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.sleeptrackersimulator.cloud.CloudOutboxDao
import com.example.sleeptrackersimulator.cloud.CloudOutboxEntity
import com.example.sleeptrackersimulator.cloud.CloudSessionEntity
import com.example.sleeptrackersimulator.data.sessions.DeviceDao
import com.example.sleeptrackersimulator.data.sessions.DeviceEntity
import com.example.sleeptrackersimulator.data.sessions.FeatureWindowDao
import com.example.sleeptrackersimulator.data.sessions.FeatureWindowEntity
import com.example.sleeptrackersimulator.data.sessions.HeartRateSampleDao
import com.example.sleeptrackersimulator.data.sessions.HeartRateSampleEntity
import com.example.sleeptrackersimulator.data.sessions.PersonalBaselineDao
import com.example.sleeptrackersimulator.data.sessions.PersonalBaselineEntity
import com.example.sleeptrackersimulator.data.sessions.PredictionDao
import com.example.sleeptrackersimulator.data.sessions.PredictionEntity
import com.example.sleeptrackersimulator.data.sessions.RecordingSessionEntity
import com.example.sleeptrackersimulator.data.sessions.SensorSampleDao
import com.example.sleeptrackersimulator.data.sessions.SensorSampleEntity
import com.example.sleeptrackersimulator.data.sessions.SessionDao
import com.example.sleeptrackersimulator.data.sessions.SessionSummaryDao
import com.example.sleeptrackersimulator.data.sessions.SessionSummaryEntity
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import com.example.sleeptrackersimulator.smart.SmartRuleDao
import com.example.sleeptrackersimulator.smart.SmartRuleEntity
import com.example.sleeptrackersimulator.smart.SmartRuleEventCoordinatorDao
import com.example.sleeptrackersimulator.smart.SmartRuleExecutionEntity

@Database(
    entities = [
        SensorRecord::class,
        ActuatorEvent::class,
        CloudOutboxEntity::class,
        CloudSessionEntity::class,
        SmartRuleEntity::class,
        SmartRuleExecutionEntity::class,
        RecordingSessionEntity::class,
        SensorSampleEntity::class,
        HeartRateSampleEntity::class,
        FeatureWindowEntity::class,
        PredictionEntity::class,
        SessionSummaryEntity::class,
        PersonalBaselineEntity::class,
        DeviceEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class SleepTrackerDatabase : RoomDatabase() {
    abstract fun sensorRecordDao(): SensorRecordDao
    abstract fun cloudOutboxDao(): CloudOutboxDao
    abstract fun smartRuleDao(): SmartRuleDao
    abstract fun smartRuleEventCoordinatorDao(): SmartRuleEventCoordinatorDao

    // Version 7 Session DAOs
    abstract fun sessionDao(): SessionDao
    abstract fun sensorSampleDao(): SensorSampleDao
    abstract fun heartRateSampleDao(): HeartRateSampleDao
    abstract fun featureWindowDao(): FeatureWindowDao
    abstract fun predictionDao(): PredictionDao
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun personalBaselineDao(): PersonalBaselineDao
    abstract fun deviceDao(): DeviceDao

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
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                ).addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        DiagnosticLog.event(
                            DiagnosticTags.ROOM,
                            "openDatabase",
                            DiagnosticResult.SUCCESS,
                            details = mapOf("database" to DATABASE_NAME, "version" to db.version),
                        )
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
                db.execSQL(
                    """INSERT INTO `smart_rule_executions`
                    (`executionId`,`ruleId`,`timestamp`,`sourceValue`,`action`,`result`,`failureReason`,`ruleNameSnapshot`,`sessionId`,`sensorTimestampNanos`,`predictionId`,`actuatorStatus`,`bleStatus`,`cloudStatus`)
                    SELECT `executionId`,`ruleId`,`timestamp`,`sourceValue`,`action`,`result`,`failureReason`,'',NULL,NULL,NULL,'UNKNOWN','UNKNOWN','NOT_QUEUED'
                    FROM `smart_rule_executions_legacy`""",
                )
                db.execSQL("DROP TABLE `smart_rule_executions_legacy`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_ruleId` ON `smart_rule_executions` (`ruleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_timestamp` ON `smart_rule_executions` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_rule_executions_cloudStatus` ON `smart_rule_executions` (`cloudStatus`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cloud_outbox` ADD COLUMN `readyForDelivery` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `smart_rule_executions` ADD COLUMN `triggerSource` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `smart_rule_executions` ADD COLUMN `mlClass` TEXT")
                db.execSQL("ALTER TABLE `smart_rule_executions` ADD COLUMN `mlConfidence` REAL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. recording_sessions
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `recording_sessions` (
                        `sessionId` TEXT NOT NULL,
                        `startUtcMillis` INTEGER NOT NULL,
                        `endUtcMillis` INTEGER,
                        `startElapsedRealtimeNanos` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `sourceMode` TEXT NOT NULL,
                        `devicePlacement` TEXT NOT NULL,
                        `requestedSamplingHz` INTEGER NOT NULL,
                        `modelVersion` TEXT,
                        `dataCoverageFraction` REAL NOT NULL,
                        `qualityStatus` TEXT NOT NULL,
                        `interruptionReason` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_sessions_startUtcMillis` ON `recording_sessions` (`startUtcMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_sessions_status` ON `recording_sessions` (`status`)")

                // 2. sensor_samples
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sensor_samples` (
                        `sampleId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `elapsedRealtimeNanos` INTEGER NOT NULL,
                        `utcTimestampMillis` INTEGER NOT NULL,
                        `x` REAL NOT NULL,
                        `y` REAL NOT NULL,
                        `z` REAL NOT NULL,
                        `sensorAccuracy` INTEGER NOT NULL,
                        `sourceKind` TEXT NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sensor_samples_sessionId_utcTimestampMillis` ON `sensor_samples` (`sessionId`, `utcTimestampMillis`)")

                // 3. heart_rate_samples
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `heart_rate_samples` (
                        `sampleId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `bpm` INTEGER NOT NULL,
                        `utcTimestampMillis` INTEGER NOT NULL,
                        `timingUncertaintyMillis` INTEGER NOT NULL,
                        `sourceKind` TEXT NOT NULL,
                        PRIMARY KEY(`sampleId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_heart_rate_samples_sessionId_utcTimestampMillis` ON `heart_rate_samples` (`sessionId`, `utcTimestampMillis`)")

                // 4. feature_windows
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `feature_windows` (
                        `windowId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `startElapsedNanos` INTEGER NOT NULL,
                        `endElapsedNanos` INTEGER NOT NULL,
                        `startUtcMillis` INTEGER NOT NULL,
                        `endUtcMillis` INTEGER NOT NULL,
                        `sampleCount` INTEGER NOT NULL,
                        `expectedSampleCount` INTEGER NOT NULL,
                        `coverageFraction` REAL NOT NULL,
                        `motionRms` REAL NOT NULL,
                        `variance` REAL NOT NULL,
                        `jerk` REAL NOT NULL,
                        `peakToPeak` REAL NOT NULL,
                        `qualityScore` REAL NOT NULL,
                        `preprocessingVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`windowId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feature_windows_sessionId_startUtcMillis` ON `feature_windows` (`sessionId`, `startUtcMillis`)")

                // 5. predictions
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `predictions` (
                        `predictionId` TEXT NOT NULL,
                        `windowId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `predictedClass` TEXT NOT NULL,
                        `probabilitiesJson` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `inferenceDurationNs` INTEGER NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`predictionId`),
                        FOREIGN KEY(`windowId`) REFERENCES `feature_windows`(`windowId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_predictions_sessionId_timestampMillis` ON `predictions` (`sessionId`, `timestampMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_predictions_windowId` ON `predictions` (`windowId`)")

                // 6. session_summaries
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `session_summaries` (
                        `sessionId` TEXT NOT NULL,
                        `observationDurationMs` INTEGER NOT NULL,
                        `validDurationMs` INTEGER NOT NULL,
                        `dataCoverageFraction` REAL NOT NULL,
                        `lowMotionFraction` REAL NOT NULL,
                        `movementEpisodeCount` INTEGER NOT NULL,
                        `movementRatePerHour` REAL NOT NULL,
                        `averageHeartRateBpm` INTEGER,
                        `sourceKind` TEXT NOT NULL,
                        `devicePlacement` TEXT NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `calculatedAtUtcMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )

                // 7. personal_baselines
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `personal_baselines` (
                        `baselineId` TEXT NOT NULL,
                        `sourceMode` TEXT NOT NULL,
                        `placement` TEXT NOT NULL,
                        `compatibleSessionCount` INTEGER NOT NULL,
                        `medianLowMotionFraction` REAL NOT NULL,
                        `madLowMotionFraction` REAL NOT NULL,
                        `medianMovementRatePerHour` REAL NOT NULL,
                        `validFromUtcMillis` INTEGER NOT NULL,
                        `validUntilUtcMillis` INTEGER,
                        `baselineVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`baselineId`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_personal_baselines_sourceMode_placement` ON `personal_baselines` (`sourceMode`, `placement`)")

                // 8. devices
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `devices` (
                        `deviceId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `sourceKind` TEXT NOT NULL,
                        `capabilitiesCsv` TEXT NOT NULL,
                        `batteryLevel` INTEGER,
                        `lastSeenUtcMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`)
                    )""".trimIndent(),
                )
            }
        }
    }
}
