package com.example.sleeptrackersimulator.di

import android.content.Context
import com.example.sleeptrackersimulator.actuator.AndroidSmartActuator
import com.example.sleeptrackersimulator.actuator.FirebaseRemoteControlRepository
import com.example.sleeptrackersimulator.actuator.RemoteControlRepository
import com.example.sleeptrackersimulator.actuator.SmartActuator
import com.example.sleeptrackersimulator.ble.IBleConnector
import com.example.sleeptrackersimulator.ble.MockBleConnector
import com.example.sleeptrackersimulator.cloud.AndroidNetworkMonitor
import com.example.sleeptrackersimulator.cloud.AuthRepository
import com.example.sleeptrackersimulator.cloud.CloudRecoveryScheduler
import com.example.sleeptrackersimulator.cloud.CloudRepository
import com.example.sleeptrackersimulator.cloud.CloudStateStore
import com.example.sleeptrackersimulator.cloud.FirebaseAnonymousAuthRepository
import com.example.sleeptrackersimulator.cloud.FirebaseCloudRepository
import com.example.sleeptrackersimulator.cloud.NetworkMonitor
import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.cloud.WorkManagerCloudRecoveryScheduler
import com.example.sleeptrackersimulator.history.RoomSensorHistoryRepository
import com.example.sleeptrackersimulator.history.SensorHistoryRepository
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import com.example.sleeptrackersimulator.ml.ActivityClassifier
import com.example.sleeptrackersimulator.sensor.AccelerometerDataSource
import com.example.sleeptrackersimulator.sensor.AndroidAccelerometerDataSource
import com.example.sleeptrackersimulator.smart.RoomSmartRuleEventCoordinator
import com.example.sleeptrackersimulator.smart.RoomSmartRuleRepository
import com.example.sleeptrackersimulator.smart.SmartRuleEventCoordinator
import com.example.sleeptrackersimulator.smart.SmartRuleRepository

/**
 * Dependency injection container managing application-level singletons and component graphs.
 * Replaces hardcoded manual wiring in MainActivity and enables clean test replacements.
 */
interface AppContainer {
    val database: SleepTrackerDatabase
    val accelerometerDataSource: AccelerometerDataSource
    val bleConnector: IBleConnector
    val smartActuator: SmartActuator
    val remoteControlRepository: RemoteControlRepository
    val cloudRepository: CloudRepository
    val authRepository: AuthRepository
    val cloudStateStore: CloudStateStore
    val cloudRecoveryScheduler: CloudRecoveryScheduler
    val networkMonitor: NetworkMonitor
    val sensorHistoryRepository: SensorHistoryRepository
    val smartRuleRepository: SmartRuleRepository
    val smartRuleEventCoordinator: SmartRuleEventCoordinator
    val activityClassifier: ActivityClassifier
    val sessionRepository: com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository
    val analyticsRepository: com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
    val sessionCoordinator: com.example.sleeptrackersimulator.domain.recording.SessionCoordinator
    val dataRetentionPolicy: com.example.sleeptrackersimulator.data.sessions.DataRetentionPolicy
    val deviceTransport: com.example.sleeptrackersimulator.data.ble.SwitchingDeviceTransport
    val virtualSleepBandAdvertiser: com.example.sleeptrackersimulator.data.ble.VirtualSleepBandAdvertiser
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val database: SleepTrackerDatabase by lazy {
        SleepTrackerDatabase.getInstance(context)
    }

    override val accelerometerDataSource: AccelerometerDataSource by lazy {
        AndroidAccelerometerDataSource(context)
    }

    override val bleConnector: IBleConnector by lazy {
        MockBleConnector()
    }

    override val smartActuator: SmartActuator by lazy {
        AndroidSmartActuator(context)
    }

    override val remoteControlRepository: RemoteControlRepository by lazy {
        FirebaseRemoteControlRepository()
    }

    override val cloudRepository: CloudRepository by lazy {
        FirebaseCloudRepository()
    }

    override val authRepository: AuthRepository by lazy {
        FirebaseAnonymousAuthRepository()
    }

    override val cloudStateStore: CloudStateStore by lazy {
        RoomCloudStateStore(
            dao = database.cloudOutboxDao(),
            legacyFile = context.filesDir.resolve("cloud/cloud-state.json"),
        )
    }

    override val cloudRecoveryScheduler: CloudRecoveryScheduler by lazy {
        WorkManagerCloudRecoveryScheduler(context)
    }

    override val networkMonitor: NetworkMonitor by lazy {
        AndroidNetworkMonitor(context)
    }

    override val sensorHistoryRepository: SensorHistoryRepository by lazy {
        RoomSensorHistoryRepository(database.sensorRecordDao())
    }

    override val smartRuleRepository: SmartRuleRepository by lazy {
        RoomSmartRuleRepository(database.smartRuleDao())
    }

    override val smartRuleEventCoordinator: SmartRuleEventCoordinator by lazy {
        RoomSmartRuleEventCoordinator(
            dao = database.smartRuleEventCoordinatorDao(),
        )
    }

    override val activityClassifier: ActivityClassifier by lazy {
        ActivityClassifier(context)
    }

    override val sessionRepository: com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository by lazy {
        com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository(
            sessionDao = database.sessionDao(),
            sensorSampleDao = database.sensorSampleDao(),
            heartRateSampleDao = database.heartRateSampleDao(),
            featureWindowDao = database.featureWindowDao(),
            predictionDao = database.predictionDao(),
            sessionSummaryDao = database.sessionSummaryDao(),
            personalBaselineDao = database.personalBaselineDao(),
            deviceDao = database.deviceDao(),
            database = database,
        )
    }

    override val analyticsRepository: com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
        get() = sessionRepository

    override val sessionCoordinator: com.example.sleeptrackersimulator.domain.recording.SessionCoordinator by lazy {
        com.example.sleeptrackersimulator.domain.recording.SessionCoordinatorImpl(
            sessionRepository = sessionRepository,
            activityClassifier = activityClassifier,
            cloudRepository = cloudRepository,
            authRepository = authRepository,
            cloudStateStore = cloudStateStore,
            cloudRecoveryScheduler = cloudRecoveryScheduler,
        )
    }

    override val dataRetentionPolicy: com.example.sleeptrackersimulator.data.sessions.DataRetentionPolicy by lazy {
        com.example.sleeptrackersimulator.data.sessions.DataRetentionPolicy(
            sensorSampleDao = database.sensorSampleDao(),
            heartRateSampleDao = database.heartRateSampleDao(),
            featureWindowDao = database.featureWindowDao(),
            predictionDao = database.predictionDao(),
        )
    }

    override val deviceTransport: com.example.sleeptrackersimulator.data.ble.SwitchingDeviceTransport by lazy {
        com.example.sleeptrackersimulator.data.ble.SwitchingDeviceTransport(
            real = com.example.sleeptrackersimulator.data.ble.AndroidGattDeviceTransport(context),
            demo = com.example.sleeptrackersimulator.data.ble.MockDeviceTransport(),
        )
    }

    override val virtualSleepBandAdvertiser: com.example.sleeptrackersimulator.data.ble.VirtualSleepBandAdvertiser by lazy {
        com.example.sleeptrackersimulator.data.ble.VirtualSleepBandAdvertiser(context)
    }
}
