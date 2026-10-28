package com.example.sleeptrackersimulator.actuator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

class AndroidSmartActuator(context: Context) : SmartActuator {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator? =
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val flashlightCameraId: String? = findFlashlightCameraId()

    override val vibratorAvailable: Boolean = vibrator?.hasVibrator() == true
    override val amplitudeControlAvailable: Boolean = vibrator?.hasAmplitudeControl() == true
    private val _observedFlashlightEnabled = MutableStateFlow<Boolean?>(null)
    override val observedFlashlightEnabled: StateFlow<Boolean?> = _observedFlashlightEnabled
    override val flashlightAvailable: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) &&
            flashlightCameraId != null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == flashlightCameraId) _observedFlashlightEnabled.value = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == flashlightCameraId) _observedFlashlightEnabled.value = null
        }
    }

    init {
        runCatching { cameraManager.registerTorchCallback(torchCallback, null) }
    }

    @Synchronized
    override fun triggerVibrationPulse(): ActuatorResult {
        if (!vibratorAvailable) return unavailable("Vibrator unavailable")
        return runOperation("Vibration pulse triggered") {
            DiagnosticLog.event(TAG, "vibrate", DiagnosticResult.STARTED, source = "LOCAL_AUTOMATIC", details = mapOf("effect" to "oneShot", "durationMs" to PULSE_DURATION_MS, "usage" to "ALARM"))
            vibrate(VibrationEffect.createOneShot(PULSE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    @Synchronized
    override fun setPersistentVibration(enabled: Boolean): ActuatorResult {
        if (!enabled && !vibratorAvailable) {
            return ActuatorResult(ActuatorOperationStatus.SUCCESS, "Vibration disabled")
        }
        if (!vibratorAvailable) return unavailable("Vibrator unavailable")
        return runOperation(if (enabled) "Vibration enabled" else "Vibration disabled") {
            if (enabled) {
                DiagnosticLog.event(TAG, "vibrate", DiagnosticResult.STARTED, details = mapOf("effect" to "waveform", "timingsMs" to PERSISTENT_TIMINGS_MS.contentToString(), "repeatIndex" to PERSISTENT_REPEAT_INDEX, "usage" to "ALARM"))
                vibrate(
                    VibrationEffect.createWaveform(
                        PERSISTENT_TIMINGS_MS,
                        PERSISTENT_REPEAT_INDEX,
                    ),
                )
            } else {
                cancelVibration("persistent vibration disabled")
            }
        }
    }

    @Synchronized
    override fun setFlashlightEnabled(enabled: Boolean): ActuatorResult {
        if (!enabled && !flashlightAvailable) {
            return ActuatorResult(ActuatorOperationStatus.SUCCESS, "Flashlight disabled")
        }
        if (!flashlightAvailable) return unavailable("Flashlight unavailable")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DiagnosticLog.event(TAG, "setFlashlight", DiagnosticResult.FAILED, details = mapOf("reason" to "camera_permission_denied"))
            return ActuatorResult(ActuatorOperationStatus.ERROR, "Camera permission required")
        }
        return runOperation(if (enabled) "Flashlight enabled" else "Flashlight disabled") {
            cameraManager.setTorchMode(requireNotNull(flashlightCameraId), enabled)
        }
    }

    @Synchronized
    override fun stopAll() {
        cancelVibration("stopAll")
        if (flashlightAvailable && ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { cameraManager.setTorchMode(requireNotNull(flashlightCameraId), false) }
                .onFailure { DiagnosticLog.event(TAG, "setFlashlight", DiagnosticResult.FAILED, source = "LIFECYCLE", details = mapOf("reason" to it.javaClass.simpleName), error = it) }
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        val availableVibrator = requireNotNull(vibrator)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            availableVibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            @Suppress("DEPRECATION")
            availableVibrator.vibrate(effect, ALARM_AUDIO_ATTRIBUTES)
        }
    }

    private fun cancelVibration(reason: String) {
        DiagnosticLog.event(TAG, "cancel", DiagnosticResult.SUCCESS, details = mapOf("reason" to reason))
        vibrator?.cancel()
    }

    private fun findFlashlightCameraId(): String? {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return null
        return runCatching {
            cameraManager.cameraIdList
                .map { it to cameraManager.getCameraCharacteristics(it) }
                .sortedByDescending { (_, characteristics) ->
                    characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                }
                .firstOrNull { (_, characteristics) ->
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }?.first
        }.onFailure { DiagnosticLog.event(TAG, "discoverFlashlight", DiagnosticResult.FAILED, details = mapOf("reason" to it.javaClass.simpleName), error = it) }.getOrNull()
    }

    private inline fun runOperation(successMessage: String, operation: () -> Unit): ActuatorResult =
        try {
            operation()
            DiagnosticLog.event(TAG, "androidApiCall", DiagnosticResult.SUCCESS, details = mapOf("physicalEffectConfirmed" to false, "message" to successMessage))
            ActuatorResult(ActuatorOperationStatus.SUCCESS, successMessage)
        } catch (error: Exception) {
            DiagnosticLog.event(TAG, "androidApiCall", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
            ActuatorResult(
                ActuatorOperationStatus.ERROR,
                error.message ?: error.javaClass.simpleName,
            )
        }

    private fun unavailable(message: String): ActuatorResult {
        DiagnosticLog.event(TAG, "hardwareAvailability", DiagnosticResult.SKIPPED, details = mapOf("reason" to message))
        return ActuatorResult(ActuatorOperationStatus.UNAVAILABLE, message)
    }

    companion object {
        private const val TAG = DiagnosticTags.ACTUATORS
        internal const val PULSE_DURATION_MS = 250L
        internal val PERSISTENT_TIMINGS_MS = longArrayOf(0L, 300L, 700L)
        internal const val PERSISTENT_REPEAT_INDEX = 1
        private val ALARM_AUDIO_ATTRIBUTES by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
        }
    }
}
