package com.example.sleeptrackersimulator.cloud

data class SensorPayload(
    val measurementId: String = "",
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
    val errorType: CloudErrorType? = null,
    val authenticated: Boolean = false,
    val droppedTelemetryCount: Long = 0,
    val storageErrorMessage: String? = null,
)

const val CLOUD_SYNC_INTERVAL_MS = 5_000L
const val CLOUD_PENDING_QUEUE_CAPACITY = 50
const val CLOUD_DEVICE_ID = "sleep-tracker-simulator"
const val CLOUD_SCHEMA_VERSION = 2
const val CLOUD_RETENTION_DAYS = 30
const val CLOUD_MAX_SESSIONS_PER_USER = 30

enum class CloudErrorType { NETWORK, AUTH, PERMISSION, VALIDATION, SERVER, UNKNOWN }

class CloudOperationException(
    val type: CloudErrorType,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val retriable: Boolean get() = type == CloudErrorType.NETWORK || type == CloudErrorType.SERVER
}

data class SmartRuleEventPayload(
    val eventId: String = "",
    val ruleId: String = "",
    val ruleName: String = "",
    val timestamp: Long = 0L,
    val source: String = "",
    val sourceValue: String = "",
    val sensorTimestampNanos: Long? = null,
    val predictionId: String? = null,
    val mlClass: String? = null,
    val mlConfidence: Float? = null,
    val requestedAction: String = "",
    val actuatorResult: String = "NOT_REQUESTED",
    val bleDeliveryResult: String = "NOT_REQUESTED",
    val schemaVersion: Int = 1,
)

enum class PendingOperationType { UPLOAD_MEASUREMENT, UPLOAD_RULE_EVENT, END_SESSION, UPLOAD_SESSION_SUMMARY }
enum class DeliveryState { PENDING, PERMANENT_FAILURE }

data class PendingCloudOperation(
    val operationId: String = "",
    val type: PendingOperationType = PendingOperationType.UPLOAD_MEASUREMENT,
    val sessionId: String = "",
    val payload: SensorPayload? = null,
    val ruleEvent: SmartRuleEventPayload? = null,
    val summary: SessionSummaryPayload? = null,
    val endedAt: Long? = null,
    val createdAt: Long = 0L,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val deliveryState: DeliveryState = DeliveryState.PENDING,
    val readyForDelivery: Boolean = true,
)

data class ActiveCloudSession(
    val sessionId: String = "",
    val startedAt: Long = 0L,
    val ownerUid: String = "",
    val monitoringActive: Boolean = true,
)

data class SessionSummaryPayload(
    val sessionId: String = "",
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val observationDurationMs: Long = 0L,
    val lowMotionFraction: Float = 0f,
    val restlessFraction: Float = 0f,
    val activeFraction: Float = 0f,
    val meanHeartRateBpm: Int? = null,
    val sourceMode: String = "",
    val placement: String = "",
    val qualityStatus: String = "",
    val schemaVersion: Int = 2,
)
