package com.example.sleeptrackersimulator.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

class AndroidAccelerometerDataSource(context: Context) : AccelerometerDataSource {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override fun observe(): Flow<AccelerometerEvent> {
        val manager = sensorManager
        val sensor = accelerometer
        if (manager == null || sensor == null) {
            DiagnosticLog.event(DiagnosticTags.SENSORS, "sensorAvailability", DiagnosticResult.FAILED, details = mapOf("reason" to "accelerometer_unavailable"))
            return flowOf(AccelerometerEvent.Unavailable)
        }

        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return

                    trySend(
                        AccelerometerEvent.Measurement(
                            SensorSample(
                                x = event.values[0],
                                y = event.values[1],
                                z = event.values[2],
                                timestampNanos = event.timestamp,
                            ),
                        ),
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            val registered = runCatching {
                DiagnosticLog.event(DiagnosticTags.SENSORS, "registerListener", DiagnosticResult.STARTED, details = mapOf("samplingPeriodUs" to 50_000))
                manager.registerListener(listener, sensor, 50_000, 0)
            }.getOrElse { error ->
                DiagnosticLog.event(DiagnosticTags.SENSORS, "registerListener", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
                trySend(AccelerometerEvent.Error(error.message ?: "Cannot access the accelerometer"))
                false
            }

            if (!registered) {
                DiagnosticLog.event(DiagnosticTags.SENSORS, "registerListener", DiagnosticResult.FAILED, details = mapOf("reason" to "registration_rejected"))
                trySend(AccelerometerEvent.Error("Cannot register the accelerometer listener"))
            } else {
                DiagnosticLog.event(DiagnosticTags.SENSORS, "registerListener", DiagnosticResult.SUCCESS)
            }

            awaitClose {
                manager.unregisterListener(listener)
                DiagnosticLog.event(DiagnosticTags.SENSORS, "unregisterListener", DiagnosticResult.SUCCESS)
            }
        }
    }
}
