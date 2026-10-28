package com.example.sleeptrackersimulator.data.ble

object HeartRatePacketParser {
    fun parse(bytes: ByteArray): Result<Int> = runCatching {
        require(bytes.size >= 2) { "Heart Rate Measurement is truncated" }
        val flags = bytes[0].toInt() and 0xFF
        val bpm = if ((flags and 0x01) != 0) {
            require(bytes.size >= 3) { "16-bit Heart Rate Measurement is truncated" }
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else bytes[1].toInt() and 0xFF
        require(bpm in 20..250) { "Implausible heart-rate value: $bpm" }
        bpm
    }
}
