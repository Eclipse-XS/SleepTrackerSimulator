package com.example.sleeptrackersimulator.cloud

class PendingPayloadQueue(private val capacity: Int = CLOUD_PENDING_QUEUE_CAPACITY) {
    private val items = ArrayDeque<SensorPayload>()

    init { require(capacity > 0) }

    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()
    fun peek(): SensorPayload = items.first()
    fun removeFirst(): SensorPayload = items.removeFirst()

    fun add(payload: SensorPayload) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(payload)
    }
}
