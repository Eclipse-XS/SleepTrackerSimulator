package com.example.sleeptrackersimulator.cloud

data class CloudSessionSummary(val sessionId: String, val startedAt: Long, val endedAt: Long?)

class CloudRetentionPolicy(
    val retentionDays: Int = CLOUD_RETENTION_DAYS,
    val maxSessions: Int = CLOUD_MAX_SESSIONS_PER_USER,
) {
    fun selectForDeletion(sessions: List<CloudSessionSummary>, activeSessionId: String?, now: Long): Set<String> {
        val completed = sessions.filter { it.sessionId != activeSessionId && it.endedAt != null }
            .sortedByDescending { it.startedAt }
        val cutoff = now - retentionDays * 24L * 60L * 60L * 1_000L
        val expired = completed.filter { it.startedAt < cutoff }.map { it.sessionId }
        val overflow = completed.drop(maxSessions).map { it.sessionId }
        return (expired + overflow).toSet()
    }
}
