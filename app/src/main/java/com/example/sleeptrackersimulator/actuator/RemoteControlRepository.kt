package com.example.sleeptrackersimulator.actuator

import kotlinx.coroutines.flow.Flow

interface RemoteControlRepository {
    val events: Flow<RemoteControlEvent>
}
