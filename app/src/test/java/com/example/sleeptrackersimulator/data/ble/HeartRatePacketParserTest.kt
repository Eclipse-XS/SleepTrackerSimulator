package com.example.sleeptrackersimulator.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRatePacketParserTest {
    @Test fun parsesEightAndSixteenBitPackets() {
        assertEquals(72, HeartRatePacketParser.parse(byteArrayOf(0, 72)).getOrThrow())
        assertEquals(180, HeartRatePacketParser.parse(byteArrayOf(1, 180.toByte(), 0)).getOrThrow())
    }

    @Test fun rejectsTruncatedAndImplausiblePackets() {
        assertTrue(HeartRatePacketParser.parse(byteArrayOf()).isFailure)
        assertTrue(HeartRatePacketParser.parse(byteArrayOf(1, 90)).isFailure)
        assertTrue(HeartRatePacketParser.parse(byteArrayOf(0, 5)).isFailure)
    }
}
