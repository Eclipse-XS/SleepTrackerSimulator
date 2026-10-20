package com.example.sleeptrackersimulator.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "actuator_events",
    indices = [Index(value = ["timestamp"])],
)
data class ActuatorEvent(
    @PrimaryKey val eventId: String,
    val timestamp: Long,
    val action: String,
    val triggerSource: String,
    val requestedState: Boolean,
    val result: String,
    val sensorRecordId: Long? = null,
)
