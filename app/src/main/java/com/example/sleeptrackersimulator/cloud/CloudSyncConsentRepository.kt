package com.example.sleeptrackersimulator.cloud

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository governing explicit user consent for Firebase Cloud Synchronization.
 * Privacy-first contract: Cloud sync MUST remain strictly opt-in and default to disabled (false).
 */
interface CloudSyncConsentRepository {
    val isCloudSyncEnabled: StateFlow<Boolean>
    fun setCloudSyncEnabled(enabled: Boolean)
}

class SharedPreferencesCloudSyncConsentRepository(
    context: Context,
) : CloudSyncConsentRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _isCloudSyncEnabled = MutableStateFlow(prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false))
    override val isCloudSyncEnabled: StateFlow<Boolean> = _isCloudSyncEnabled.asStateFlow()

    override fun setCloudSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled).apply()
        _isCloudSyncEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "cloud_sync_consent_prefs"
        private const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
    }
}
