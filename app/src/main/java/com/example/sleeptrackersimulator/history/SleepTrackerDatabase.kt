package com.example.sleeptrackersimulator.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
                ).build().also { instance = it }
            }
    }
}
