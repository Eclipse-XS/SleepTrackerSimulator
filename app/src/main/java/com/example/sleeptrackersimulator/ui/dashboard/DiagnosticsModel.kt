package com.example.sleeptrackersimulator.ui.dashboard

/** Honest HR-stream health: a subscription alone never proves a healthy stream. */
enum class HrStreamHealth { NO_SAMPLES, STALE, LIVE }

object DiagnosticsModel {
    const val RECENT_SAMPLE_WINDOW_NANOS = 5_000_000_000L

    /** [sampleTimestampNanos] and [nowNanos] must come from the same System.nanoTime() clock. */
    fun hrStreamHealth(sampleTimestampNanos: Long?, nowNanos: Long): HrStreamHealth = when {
        sampleTimestampNanos == null -> HrStreamHealth.NO_SAMPLES
        nowNanos - sampleTimestampNanos in 0..RECENT_SAMPLE_WINDOW_NANOS -> HrStreamHealth.LIVE
        else -> HrStreamHealth.STALE
    }

    fun healthLabel(health: HrStreamHealth, subscribed: Boolean): String = when (health) {
        HrStreamHealth.LIVE -> "HR stream LIVE (sample < 5 s ago)"
        HrStreamHealth.STALE -> if (subscribed) "Subscribed, but NO RECENT HR SAMPLES (> 5 s)" else "No recent HR samples"
        HrStreamHealth.NO_SAMPLES -> if (subscribed) "Subscribed, but NO HR SAMPLES received" else "No HR samples received"
    }

    fun cloudErrorSummary(pending: Int, error: String?, storageError: String?): String? {
        if (error == null && storageError == null) return null
        val parts = mutableListOf("$pending pending operation(s) kept in the durable queue")
        error?.let { parts += "Sync error: $it" }
        storageError?.let { parts += "Storage error: $it" }
        return parts.joinToString("\n")
    }
}
