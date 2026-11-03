package com.example.sleeptrackersimulator.ui.developer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.sleeptrackersimulator.actuator.ActuatorUiState
import com.example.sleeptrackersimulator.ui.theme.SleepTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeveloperLabActuatorScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun flashlightOffButtonDispatchesFalse() {
        val commands = mutableListOf<Boolean>()
        composeRule.setContent {
            SleepTrackerTheme {
                DeveloperLabScreen(
                    onBack = {},
                    actuatorState = ActuatorUiState(flashlightAvailable = true),
                    onTriggerVibration = {},
                    onFlashlightChanged = commands::add,
                    onTriggerBleAlert = {},
                )
            }
        }

        composeRule.onNodeWithText("Actuators").performScrollTo().performClick()
        composeRule.onNodeWithText("Flashlight OFF").performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(listOf(false), commands) }
    }

    @Test
    fun actuatorPanelExposesIndependentSafetyControls() {
        val vibrationCommands = mutableListOf<Boolean>()
        var emergencyCalls = 0
        composeRule.setContent {
            SleepTrackerTheme {
                DeveloperLabScreen(
                    onBack = {},
                    actuatorState = ActuatorUiState(
                        vibratorAvailable = true,
                        flashlightAvailable = true,
                        observedFlashlightEnabled = false,
                    ),
                    onTriggerVibration = {},
                    onPersistentVibrationChanged = vibrationCommands::add,
                    onEmergencyOff = { emergencyCalls++ },
                    onTriggerBleAlert = {},
                )
            }
        }

        composeRule.onNodeWithText("Actuators").performScrollTo().performClick()
        composeRule.onNodeWithText("Flashlight ON").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Flashlight OFF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Emergency OFF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Persistent Vibration OFF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("TorchCallback observed: OFF").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Persistent Vibration OFF").performScrollTo().performClick()
        composeRule.onNodeWithText("Emergency OFF").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(false), vibrationCommands)
            assertEquals(1, emergencyCalls)
        }
    }
}
