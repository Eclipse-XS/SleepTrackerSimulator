package com.example.sleeptrackersimulator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sleeptrackersimulator.sensor.AndroidAccelerometerDataSource
import com.example.sleeptrackersimulator.ui.dashboard.SensorScreen
import com.example.sleeptrackersimulator.ui.dashboard.SensorViewModel
import com.example.sleeptrackersimulator.ui.theme.SleepTrackerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SensorViewModel by viewModels {
        SensorViewModel.factory(AndroidAccelerometerDataSource(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SleepTrackerTheme {
                val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
                SensorScreen(uiState = uiState, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
