package com.example.sleeptrackersimulator.diagnostics

import android.util.Log
import com.example.sleeptrackersimulator.BuildConfig

enum class DiagnosticResult { STARTED, SUCCESS, FAILED, SKIPPED }

object DiagnosticTags {
    const val SENSORS = "SleepTracker-Sensors"
    const val BLE = "SleepTracker-BLE"
    const val CLOUD = "SleepTracker-Cloud"
    const val ACTUATORS = "SleepTracker-Actuators"
    const val ROOM = "SleepTracker-Room"
    const val ANALYTICS = "SleepTracker-Analytics"
    const val LIFECYCLE = "SleepTracker-Lifecycle"
}

data class DiagnosticEvent(
    val operation: String,
    val result: DiagnosticResult,
    val source: String? = null,
    val correlationId: String? = null,
    val details: Map<String, Any?> = emptyMap(),
) {
    fun format(): String = buildList {
        add("operation=${sanitize(operation)}")
        add("result=$result")
        source?.let { add("source=${sanitize(it)}") }
        correlationId?.let { add("correlationId=${sanitize(it)}") }
        details.toSortedMap().forEach { (key, value) -> add("${sanitize(key)}=${sanitize(value)}") }
    }.joinToString(" ")

    private fun sanitize(value: Any?): String = value.toString()
        .replace(Regex("[\\r\\n\\t]+"), "_")
        .replace(' ', '_')
}

object DiagnosticLog {
    fun event(
        tag: String,
        operation: String,
        result: DiagnosticResult,
        source: String? = null,
        correlationId: String? = null,
        details: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val message = DiagnosticEvent(operation, result, source, correlationId, details).format()
        try {
            when (result) {
                DiagnosticResult.STARTED -> Log.d(tag, message)
                DiagnosticResult.SUCCESS -> Log.i(tag, message)
                DiagnosticResult.SKIPPED -> Log.w(tag, message)
                DiagnosticResult.FAILED -> Log.e(tag, message, error)
            }
        } catch (_: RuntimeException) {
            // android.util.Log is unavailable in local JVM unit tests.
        }
    }
}
