package com.example.sleeptrackersimulator.cloud

fun interface SmartRuleCloudStatusUpdater {
    suspend fun markSynced(eventId: String)
}

object NoOpSmartRuleCloudStatusUpdater : SmartRuleCloudStatusUpdater {
    override suspend fun markSynced(eventId: String) = Unit
}
