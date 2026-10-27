package com.example.sleeptrackersimulator.history

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SleepTrackerMigrationTest {
    @Test
    fun migrateVersion2To4PreservesSensorAndActuatorHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-2-3.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sensor_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, sensorValue REAL NOT NULL, actionTriggered INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX index_sensor_records_timestamp ON sensor_records(timestamp)")
                        db.execSQL("CREATE TABLE actuator_events (eventId TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, action TEXT NOT NULL, triggerSource TEXT NOT NULL, requestedState INTEGER NOT NULL, result TEXT NOT NULL, sensorRecordId INTEGER)")
                        db.execSQL("CREATE INDEX index_actuator_events_timestamp ON actuator_events(timestamp)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.execSQL("INSERT INTO sensor_records(timestamp,sensorValue,actionTriggered) VALUES(1000,0.5,1)")
        helper.writableDatabase.execSQL("INSERT INTO actuator_events(eventId,timestamp,action,triggerSource,requestedState,result,sensorRecordId) VALUES('e1',1000,'VIBRATION_PULSE','MANUAL',1,'SUCCESS',1)")
        helper.close()

        val migrated = Room.databaseBuilder(context, SleepTrackerDatabase::class.java, name)
            .addMigrations(
                SleepTrackerDatabase.MIGRATION_1_2,
                SleepTrackerDatabase.MIGRATION_2_3,
                SleepTrackerDatabase.MIGRATION_3_4,
                SleepTrackerDatabase.MIGRATION_4_5,
                SleepTrackerDatabase.MIGRATION_5_6,
                SleepTrackerDatabase.MIGRATION_6_7,
            )
            .build()
        runBlocking(Dispatchers.IO) {
            val dao = migrated.sensorRecordDao()
            assertEquals(1, dao.recordsBetween(1, 2_000).size)
            assertEquals(1, dao.observeActuatorEventCount(1).first())
            assertEquals(0, migrated.cloudOutboxDao().count())
            assertEquals(emptyList<com.example.sleeptrackersimulator.smart.SmartRule>(), com.example.sleeptrackersimulator.smart.RoomSmartRuleRepository(migrated.smartRuleDao()).observeAll().first())
        }
        migrated.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migrateVersion1Through4PreservesOriginalSensorRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-1-4.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sensor_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, sensorValue REAL NOT NULL, actionTriggered INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX index_sensor_records_timestamp ON sensor_records(timestamp)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.execSQL("INSERT INTO sensor_records(timestamp,sensorValue,actionTriggered) VALUES(1000,0.5,0)")
        helper.close()
        val migrated = Room.databaseBuilder(context, SleepTrackerDatabase::class.java, name)
            .addMigrations(
                SleepTrackerDatabase.MIGRATION_1_2,
                SleepTrackerDatabase.MIGRATION_2_3,
                SleepTrackerDatabase.MIGRATION_3_4,
                SleepTrackerDatabase.MIGRATION_4_5,
                SleepTrackerDatabase.MIGRATION_5_6,
                SleepTrackerDatabase.MIGRATION_6_7,
            ).build()
        runBlocking(Dispatchers.IO) { assertEquals(1, migrated.sensorRecordDao().recordsBetween(1, 2_000).size) }
        migrated.close()
        context.deleteDatabase(name)
    }
}
