package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.SensorSample

class SensorWindowBuffer(private val capacity: Int = 20) {
    init { require(capacity > 0) }

    private val values = Array(capacity) { FloatArray(3) }
    private var next = 0
    private var size = 0
    private var lastTimestampNanos = 0L

    @Synchronized
    fun add(sample: SensorSample): Array<FloatArray>? {
        require(sample.x.isFinite() && sample.y.isFinite() && sample.z.isFinite())
        require(sample.timestampNanos > 0L)
        if (lastTimestampNanos != 0L) {
            val delta = sample.timestampNanos - lastTimestampNanos
            if (delta <= 0L || delta > MAX_GAP_NANOS) clear()
        }
        lastTimestampNanos = sample.timestampNanos
        values[next][0] = sample.x
        values[next][1] = sample.y
        values[next][2] = sample.z
        next = (next + 1) % capacity
        if (size < capacity) size++
        if (size < capacity) return null
        return Array(capacity) { offset -> values[(next + offset) % capacity].copyOf() }
    }

    @Synchronized
    fun clear() {
        next = 0
        size = 0
        lastTimestampNanos = 0L
    }

    companion object {
        /** Training spans one second over 20 samples; gaps above 100 ms start a new window. */
        const val MAX_GAP_NANOS = 100_000_000L
    }
}
