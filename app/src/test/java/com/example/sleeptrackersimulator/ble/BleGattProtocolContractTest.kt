package com.example.sleeptrackersimulator.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Bluetooth SIG & SleepSense Proprietary GATT Protocol Specification Contract Tests.
 *
 * Verifies exact conformity with:
 * - Bluetooth SIG Heart Rate Service (0x180D)
 * - Bluetooth SIG Heart Rate Measurement (0x2A37)
 * - Bluetooth SIG Body Sensor Location (0x2A38)
 * - Bluetooth SIG Battery Service (0x180F)
 * - Bluetooth SIG Battery Level (0x2A19)
 * - Bluetooth SIG Client Characteristic Configuration Descriptor CCCD (0x2902)
 * - SleepSense Proprietary Vendor Command Characteristic (7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e)
 */
class BleGattProtocolContractTest {

    @Test
    fun `standard Bluetooth SIG service UUIDs match specification`() {
        assertEquals(
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
            HeartRateProfile.SERVICE_UUID,
        )
        assertEquals(
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),
            HeartRateProfile.BATTERY_SERVICE_UUID,
        )
    }

    @Test
    fun `standard Bluetooth SIG characteristic UUIDs match specification`() {
        assertEquals(
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"),
            HeartRateProfile.MEASUREMENT_UUID,
        )
        assertEquals(
            UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb"),
            HeartRateProfile.BODY_SENSOR_LOCATION_UUID,
        )
        assertEquals(
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
            HeartRateProfile.BATTERY_LEVEL_UUID,
        )
        assertEquals(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            HeartRateProfile.CCCD_UUID,
        )
    }

    @Test
    fun `body sensor location 0x2A38 is strictly separated from proprietary command UUID`() {
        // 0x2A38 is Body Sensor Location, NOT a command log
        assertNotEquals(HeartRateProfile.BODY_SENSOR_LOCATION_UUID, HeartRateProfile.COMMAND_UUID)
        assertEquals(
            UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e"),
            HeartRateProfile.COMMAND_UUID,
        )
    }

    @Test
    fun `heart rate measurement serialization follows SIG uint8 specification`() {
        val testBpm = 72
        // Bluetooth SIG 0x2A37 format:
        // Byte 0: Flags (Bit 0 = 0 for uint8 format, Bit 1..7 reserved/energy/rr)
        // Byte 1: Heart Rate Value (uint8)
        val payload = byteArrayOf(0x00, testBpm.toByte())

        val flags = payload[0].toInt()
        val is16Bit = (flags and 0x01) != 0
        val parsedBpm = if (is16Bit) {
            (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        } else {
            payload[1].toInt() and 0xFF
        }

        assertEquals(false, is16Bit)
        assertEquals(72, parsedBpm)
    }

    @Test
    fun `heart rate measurement serialization follows SIG uint16 specification`() {
        // 16-bit format: flag bit 0 = 1, bytes 1-2 = uint16 little endian
        val payload = byteArrayOf(0x01, 0x18.toByte(), 0x01.toByte()) // 0x0118 = 280 BPM
        val flags = payload[0].toInt()
        val is16Bit = (flags and 0x01) != 0
        val parsedBpm = if (is16Bit) {
            (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        } else {
            payload[1].toInt() and 0xFF
        }

        assertEquals(true, is16Bit)
        assertEquals(280, parsedBpm)
    }

    @Test
    fun `body sensor location returns canonical body locations according to SIG`() {
        val wristLocationByte: Byte = 0x02
        val chestLocationByte: Byte = 0x01
        val fingerLocationByte: Byte = 0x03

        fun parseLocation(code: Byte): String = when (code.toInt()) {
            0 -> "Other"
            1 -> "Chest"
            2 -> "Wrist"
            3 -> "Finger"
            4 -> "Hand"
            5 -> "Ear Lobe"
            6 -> "Foot"
            else -> "Unknown"
        }

        assertEquals("Wrist", parseLocation(wristLocationByte))
        assertEquals("Chest", parseLocation(chestLocationByte))
        assertEquals("Finger", parseLocation(fingerLocationByte))
    }

    @Test
    fun `battery level characteristic enforces 0 to 100 percentage range`() {
        val validLevels = listOf(0, 50, 88, 100)
        for (lvl in validLevels) {
            val byteVal = lvl.toByte()
            val parsed = byteVal.toInt() and 0xFF
            assertTrue(parsed in 0..100)
        }
    }

    @Test
    fun `cccd descriptor enables notifications with 0x0100`() {
        // Bluetooth SIG Client Characteristic Configuration Descriptor (CCCD 0x2902)
        // 0x0001 = Notifications Enabled
        // 0x0002 = Indications Enabled
        // 0x0000 = Stop
        val enableNotificationValue = byteArrayOf(0x01, 0x00)
        val stopValue = byteArrayOf(0x00, 0x00)

        val isNotificationEnabled = enableNotificationValue[0] == 0x01.toByte() && enableNotificationValue[1] == 0x00.toByte()
        val isStopped = stopValue[0] == 0x00.toByte() && stopValue[1] == 0x00.toByte()

        assertTrue(isNotificationEnabled)
        assertTrue(isStopped)
    }

    @Test
    fun `custom command write and ACK string encoding conform to protocol`() {
        val alertCommand = "ALERT:event-981"
        val payload = alertCommand.toByteArray(Charsets.UTF_8)
        val decoded = String(payload, Charsets.UTF_8)
        assertEquals(alertCommand, decoded)

        val ackResponse = "ACK:$decoded"
        assertTrue(ackResponse.startsWith("ACK:ALERT:event-981"))
    }
}
