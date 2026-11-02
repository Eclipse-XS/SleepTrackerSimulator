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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

class AndroidSmartActuator(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    injectedVibrator: Vibrator? = null,
    injectedCameraManager: CameraManager? = null,
    injectedFlashlightCameraId: String? = null,
) : SmartActuator {
    private val appContext = context?.applicationContext
    private val vibrator: Vibrator? = injectedVibrator ?: runCatching {
        appContext?.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull()
    private val cameraManager = injectedCameraManager ?: runCatching {
        appContext?.getSystemService(CameraManager::class.java)
    }.getOrNull()
    private val flashlightCameraId: String? = injectedFlashlightCameraId ?: findFlashlightCameraId()
    private var autoCutoffJob: Job? = null
    @Volatile private var isPersistentVibrating = false

    override val vibratorAvailable: Boolean = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)
    override val amplitudeControlAvailable: Boolean = runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)
    private val _observedFlashlightEnabled = MutableStateFlow<Boolean?>(null)
    override val observedFlashlightEnabled: StateFlow<Boolean?> = _observedFlashlightEnabled
    override val flashlightAvailable: Boolean =
        (runCatching { appContext?.packageManager?.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) == true }.getOrDefault(false)) &&
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
        runCatching { cameraManager?.registerTorchCallback(torchCallback, null) }
    }

    @Synchronized
    override fun triggerVibrationPulse(): ActuatorResult {
        if (!vibratorAvailable) return unavailable("Vibrator unavailable")
        return runOperation("Vibration pulse triggered") {
            DiagnosticLog.event(TAG, "vibrate", DiagnosticResult.STARTED, source = "LOCAL_AUTOMATIC", details = mapOf("effect" to "oneShot", "durationMs" to PULSE_DURATION_MS, "usage" to "ALARM"))
            val effect = runCatching {
                VibrationEffect.createOneShot(PULSE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            }.getOrNull()
            vibrate(effect)
        }
    }

    @Synchronized
    override fun setPersistentVibration(enabled: Boolean): ActuatorResult {
        if (!enabled && !vibratorAvailable) {
            isPersistentVibrating = false
            autoCutoffJob?.cancel()
            autoCutoffJob = null
            return ActuatorResult(ActuatorOperationStatus.SUCCESS, "Vibration disabled")
        }
        if (!vibratorAvailable) return unavailable("Vibrator unavailable")
        if (enabled && isPersistentVibrating) {
            return ActuatorResult(ActuatorOperationStatus.SUCCESS, "Vibration already active")
        }
        return runOperation(if (enabled) "Vibration enabled" else "Vibration disabled") {
            if (enabled) {
                isPersistentVibrating = true
                DiagnosticLog.event(TAG, "vibrate", DiagnosticResult.STARTED, details = mapOf("effect" to "waveform", "timingsMs" to PERSISTENT_TIMINGS_MS.contentToString(), "repeatIndex" to PERSISTENT_REPEAT_INDEX, "usage" to "ALARM", "safetyTimeoutMs" to MAX_PERSISTENT_VIBRATION_MS))
                val effect = runCatching {
                    VibrationEffect.createWaveform(
                        PERSISTENT_TIMINGS_MS,
                        PERSISTENT_REPEAT_INDEX,
                    )
                }.getOrNull()
                vibrate(effect)
                autoCutoffJob?.cancel()
                autoCutoffJob = scope.launch {
                    delay(MAX_PERSISTENT_VIBRATION_MS)
                    synchronized(this@AndroidSmartActuator) {
                        if (isPersistentVibrating) {
                            cancelVibration("safety_auto_cutoff_${MAX_PERSISTENT_VIBRATION_MS}ms")
                            isPersistentVibrating = false
                        }
                    }
                }
            } else {
                isPersistentVibrating = false
                autoCutoffJob?.cancel()
                autoCutoffJob = null
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
        val ctx = appContext ?: return ActuatorResult(ActuatorOperationStatus.ERROR, "Camera permission required")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DiagnosticLog.event(TAG, "setFlashlight", DiagnosticResult.FAILED, details = mapOf("reason" to "camera_permission_denied"))
            return ActuatorResult(ActuatorOperationStatus.ERROR, "Camera permission required")
        }
        return runOperation(if (enabled) "Flashlight enabled" else "Flashlight disabled") {
            cameraManager?.setTorchMode(requireNotNull(flashlightCameraId), enabled)
        }
    }

    @Synchronized
    override fun stopAll() {
        isPersistentVibrating = false
        autoCutoffJob?.cancel()
        autoCutoffJob = null
        cancelVibration("stopAll")
        val ctx = appContext
        if (flashlightAvailable && ctx != null && ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { cameraManager?.setTorchMode(requireNotNull(flashlightCameraId), false) }
                .onFailure { DiagnosticLog.event(TAG, "setFlashlight", DiagnosticResult.FAILED, source = "LIFECYCLE", details = mapOf("reason" to it.javaClass.simpleName), error = it) }
        }
    }

    private fun vibrate(effect: VibrationEffect?) {
        val availableVibrator = requireNotNull(vibrator)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attr = runCatching {
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            }.getOrNull()
            if (attr != null && effect != null) {
                availableVibrator.vibrate(effect, attr)
            } else if (effect != null) {
                availableVibrator.vibrate(effect)
            } else {
                availableVibrator.vibrate(PULSE_DURATION_MS)
            }
        } else {
            val audioAttr = ALARM_AUDIO_ATTRIBUTES
            if (audioAttr != null && effect != null) {
                @Suppress("DEPRECATION")
                availableVibrator.vibrate(effect, audioAttr)
            } else if (effect != null) {
                availableVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                availableVibrator.vibrate(PULSE_DURATION_MS)
            }
        }
    }

    private fun cancelVibration(reason: String) {
        DiagnosticLog.event(TAG, "cancel", DiagnosticResult.SUCCESS, details = mapOf("reason" to reason))
        vibrator?.cancel()
    }

    private fun findFlashlightCameraId(): String? {
        val appCtx = appContext ?: return null
        val cm = cameraManager ?: return null
        if (!appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return null
        return runCatching {
            cm.cameraIdList
                .map { it to cm.getCameraCharacteristics(it) }
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

    @androidx.annotation.VisibleForTesting
    internal val testTorchCallback: CameraManager.TorchCallback get() = torchCallback

    @androidx.annotation.VisibleForTesting
    internal val testIsPersistentVibrating: Boolean get() = isPersistentVibrating

    @androidx.annotation.VisibleForTesting
    internal val testFlashlightCameraId: String? get() = flashlightCameraId

    companion object {
        private const val TAG = DiagnosticTags.ACTUATORS
        internal const val PULSE_DURATION_MS = 250L
        internal const val MAX_PERSISTENT_VIBRATION_MS = 30_000L
        internal val PERSISTENT_TIMINGS_MS = longArrayOf(0L, 300L, 700L)
        internal const val PERSISTENT_REPEAT_INDEX = 1
        private val ALARM_AUDIO_ATTRIBUTES by lazy {
            runCatching {
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
            }.getOrNull()
        }
    }
}
