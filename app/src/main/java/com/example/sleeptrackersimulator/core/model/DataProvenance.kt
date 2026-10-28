package com.example.sleeptrackersimulator.core.model

/**
 * Data provenance contract defining the authentic origin of any signal or metric.
 * Crucial for distinguishing actual physiological/device movement from simulated lab data.
 */
enum class SourceKind {
    /** Real physical accelerometer hardware of the current Android phone */
    PHONE_REAL,

    /** Real Bluetooth Low Energy peripheral (smart band, chest strap, smartwatch) */
    BLE_REAL,

    /** BLE transport is real, but physiological authenticity is not established. */
    BLE_UNVERIFIED,

    /** Synthetic generated signal (e.g., VirtualSleepBand or in-memory demo) */
    SIMULATED,

    /** Recorded stream replayed deterministically from storage */
    REPLAY,

    /** Data imported from external storage or health platforms (e.g. Health Connect) */
    IMPORTED;

    val isRealData: Boolean
        get() = this == PHONE_REAL || this == BLE_REAL

    val isSimulated: Boolean
        get() = this == SIMULATED

    val isReplay: Boolean
        get() = this == REPLAY
}

/**
 * Categorization of physical device placement relative to user.
 * Essential because accelerometer signals differ drastically depending on placement.
 */
enum class DevicePlacement(val label: String) {
    UNKNOWN("Unknown"),
    NIGHTSTAND("Nightstand / Flat Surface"),
    MATTRESS("Mattress / Near Pillow"),
    BODY_CHEST("Body (Chest Strap)"),
    BODY_WRIST("Body (Wrist Wearable)");

    companion object {
        fun fromString(value: String?): DevicePlacement =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}
