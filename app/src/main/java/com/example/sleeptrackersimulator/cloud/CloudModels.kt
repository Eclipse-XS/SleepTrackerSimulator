package com.example.sleeptrackersimulator.cloud

data class SensorPayload(
    val deviceId: String = "",
    val sensorType: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val value: Float = 0f,
    val timestamp: Long = 0L,
    val movementState: String = "",
    val movementIntensity: Float = 0f,
    val heartRateBpm: Int? = null,
    val bleConnected: Boolean = false,
    val restState: String = "",
)

enum class CloudSyncStatus { IDLE, SYNCING, SYNCED, PENDING, ERROR }

data class CloudSyncUiState(
    val status: CloudSyncStatus = CloudSyncStatus.IDLE,
    val monitoringActive: Boolean = false,
    val sessionId: String? = null,
    val lastSuccessfulSyncAt: Long? = null,
    val uploadedCount: Int = 0,
    val pendingCount: Int = 0,
    val lastErrorMessage: String? = null,
)

const val CLOUD_SYNC_INTERVAL_MS = 5_000L
const val CLOUD_PENDING_QUEUE_CAPACITY = 50
const val CLOUD_DEVICE_ID = "sleep-tracker-simulator"
