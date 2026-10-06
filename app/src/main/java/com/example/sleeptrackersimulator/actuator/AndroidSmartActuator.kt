package com.example.sleeptrackersimulator.actuator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat

class AndroidSmartActuator(context: Context) : SmartActuator {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator? =
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val flashlightCameraId: String? = findFlashlightCameraId()

    override val vibratorAvailable: Boolean = vibrator?.hasVibrator() == true
    override val flashlightAvailable: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) &&
            flashlightCameraId != null

    override fun triggerVibrationPulse(): ActuatorResult {
        if (!vibratorAvailable) return unavailable("Vibrator unavailable")
        return runOperation("Vibration pulse triggered") {
            vibrator?.vibrate(VibrationEffect.createOneShot(PULSE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun setPersistentVibration(enabled: Boolean): ActuatorResult {
        if (!enabled && !vibratorAvailable) {
            return ActuatorResult(ActuatorOperationStatus.SUCCESS, "Vibration disabled")
        }
        if (!vibratorAvailable) return unavailable("Vibrator unavailable")
        return runOperation(if (enabled) "Vibration enabled" else "Vibration disabled") {
            if (enabled) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0L, 300L, 700L), 0),
                )
            } else {
                vibrator?.cancel()
            }
        }
    }

    override fun setFlashlightEnabled(enabled: Boolean): ActuatorResult {
        if (!enabled && !flashlightAvailable) {
            return ActuatorResult(ActuatorOperationStatus.SUCCESS, "Flashlight disabled")
        }
        if (!flashlightAvailable) return unavailable("Flashlight unavailable")
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ActuatorResult(ActuatorOperationStatus.ERROR, "Camera permission required")
        }
        return runOperation(if (enabled) "Flashlight enabled" else "Flashlight disabled") {
            cameraManager.setTorchMode(requireNotNull(flashlightCameraId), enabled)
        }
    }

    override fun stopAll() {
        vibrator?.cancel()
        if (flashlightAvailable && ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { cameraManager.setTorchMode(requireNotNull(flashlightCameraId), false) }
                .onFailure { Log.w(TAG, "Failed to disable flashlight during cleanup", it) }
        }
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
        }.onFailure { Log.w(TAG, "Flashlight discovery failed", it) }.getOrNull()
    }

    private inline fun runOperation(successMessage: String, operation: () -> Unit): ActuatorResult =
        try {
            operation()
            ActuatorResult(ActuatorOperationStatus.SUCCESS, successMessage)
        } catch (error: Exception) {
            Log.e(TAG, "Actuator operation failed", error)
            ActuatorResult(
                ActuatorOperationStatus.ERROR,
                error.message ?: error.javaClass.simpleName,
            )
        }

    private fun unavailable(message: String): ActuatorResult {
        Log.w(TAG, message)
        return ActuatorResult(ActuatorOperationStatus.UNAVAILABLE, message)
    }

    companion object {
        private const val TAG = "ActuatorControl"
        private const val PULSE_DURATION_MS = 250L
    }
}
