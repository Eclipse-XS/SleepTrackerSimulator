package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.SensorSample

class SensorWindowBuffer(private val capacity: Int = WINDOW_SIZE) {
    private val samples = arrayOfNulls<SensorSample>(capacity)
    private var writeIndex = 0
    var size: Int = 0
        private set

    init { require(capacity > 0) }

    fun add(sample: SensorSample): List<SensorSample>? {
        samples[writeIndex] = sample
        writeIndex = (writeIndex + 1) % capacity
        if (size < capacity) size++
        return if (size == capacity) snapshot() else null
    }

    fun clear() {
        samples.fill(null)
        writeIndex = 0
        size = 0
    }

    fun snapshot(): List<SensorSample> {
        if (size == 0) return emptyList()
        val start = if (size == capacity) writeIndex else 0
        return List(size) { offset ->
            samples[(start + offset) % capacity] ?: error("Incomplete sensor window")
        }
    }

    companion object { const val WINDOW_SIZE = 20 }
}
