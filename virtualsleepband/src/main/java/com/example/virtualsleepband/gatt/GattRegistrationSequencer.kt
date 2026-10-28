package com.example.virtualsleepband.gatt

import java.util.UUID

internal enum class RegistrationAction { ADD_BATTERY, START_ADVERTISING, FAIL, IGNORE }

internal class GattRegistrationSequencer {
    private enum class Stage { IDLE, WAITING_HEART_RATE, WAITING_BATTERY, COMPLETE, FAILED }
    private var stage = Stage.IDLE

    @Synchronized fun start() { stage = Stage.WAITING_HEART_RATE }
    @Synchronized fun stop() { stage = Stage.IDLE }

    @Synchronized fun onServiceAdded(statusOk: Boolean, serviceUuid: UUID?): RegistrationAction {
        if (stage == Stage.IDLE || stage == Stage.COMPLETE || stage == Stage.FAILED) return RegistrationAction.IGNORE
        if (!statusOk || serviceUuid == null) {
            stage = Stage.FAILED
            return RegistrationAction.FAIL
        }
        return when {
            stage == Stage.WAITING_HEART_RATE && serviceUuid == VirtualGattServer.UUID_HEART_RATE_SERVICE -> {
                stage = Stage.WAITING_BATTERY
                RegistrationAction.ADD_BATTERY
            }
            stage == Stage.WAITING_BATTERY && serviceUuid == VirtualGattServer.UUID_BATTERY_SERVICE -> {
                stage = Stage.COMPLETE
                RegistrationAction.START_ADVERTISING
            }
            else -> {
                stage = Stage.FAILED
                RegistrationAction.FAIL
            }
        }
    }
}
