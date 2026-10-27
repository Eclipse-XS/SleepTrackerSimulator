package com.example.virtualsleepband

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.virtualsleepband.gatt.VirtualGattServer
import com.example.virtualsleepband.ui.VirtualBandScreen

class MainActivity : ComponentActivity() {

    private lateinit var gattServer: VirtualGattServer

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        if (!granted) {
            Toast.makeText(this, "Bluetooth permissions are required for BLE advertising", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        gattServer = VirtualGattServer(this)

        checkAndRequestPermissions()

        setContent {
            val state by gattServer.state.collectAsStateWithLifecycle()

            VirtualBandScreen(
                state = state,
                onToggleAdvertising = {
                    if (state.isAdvertising) {
                        gattServer.stopAdvertising()
                    } else {
                        val result = gattServer.startAdvertising()
                        if (result.isFailure) {
                            Toast.makeText(
                                this,
                                result.exceptionOrNull()?.message ?: "Failed to start advertising",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                onSetBpm = { gattServer.setBpm(it) },
                onSetBattery = { gattServer.setBatteryLevel(it) },
                onSetInterval = { gattServer.setPacketInterval(it) },
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gattServer.stopAdvertising()
    }
}
