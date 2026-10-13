package com.example.sleeptrackersimulator.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

@Database(entities = [SensorRecord::class], version = 1, exportSchema = true)
abstract class SleepTrackerDatabase : RoomDatabase() {
    abstract fun sensorRecordDao(): SensorRecordDao

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
                ).addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        DiagnosticLog.event(DiagnosticTags.ROOM, "openDatabase", DiagnosticResult.SUCCESS, details = mapOf("database" to DATABASE_NAME, "version" to db.version))
                    }
                }).build().also { instance = it }
            }
    }
}
