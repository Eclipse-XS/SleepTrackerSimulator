package com.example.sleeptrackersimulator.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sensor_records",
    indices = [Index(value = ["timestamp"])],
)
data class SensorRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val sensorValue: Float,
    val actionTriggered: Boolean,
)
