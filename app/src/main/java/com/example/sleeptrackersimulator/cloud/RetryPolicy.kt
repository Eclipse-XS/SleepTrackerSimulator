package com.example.sleeptrackersimulator.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

class RetryPolicy(
    val delaysMillis: List<Long> = listOf(500L, 1_000L, 2_000L),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun <T> execute(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudOperationException) {
                if (!failure.retriable || attempt >= delaysMillis.size) throw failure
                val delayMillis = delaysMillis[attempt]
                DiagnosticLog.event(DiagnosticTags.CLOUD, "retryBackoff", DiagnosticResult.STARTED, details = mapOf("attempt" to attempt + 1, "delayMs" to delayMillis, "errorType" to failure.type))
                attempt++
                sleeper(delayMillis)
            }
        }
    }
}
