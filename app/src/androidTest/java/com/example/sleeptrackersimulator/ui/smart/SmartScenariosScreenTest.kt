package com.example.sleeptrackersimulator.ui.smart

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.sleeptrackersimulator.smart.RuleAction
import com.example.sleeptrackersimulator.smart.RuleOperator
import com.example.sleeptrackersimulator.smart.RuleSource
import com.example.sleeptrackersimulator.smart.SmartRule
import com.example.sleeptrackersimulator.smart.SmartScenariosUiState
import com.example.sleeptrackersimulator.ui.theme.SleepTrackerTheme
import org.junit.Rule
import org.junit.Test

class SmartScenariosScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateShowsGuidanceAndSafetyBanner() {
        composeRule.setContent {
            SleepTrackerTheme {
                SmartScenariosScreen(
                    state = SmartScenariosUiState(),
                    onSave = {},
                    onEnabled = { _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Automations & Rules").assertIsDisplayed()
        composeRule.onNodeWithText("Hardware Safety Guard").assertIsDisplayed()
        composeRule.onNodeWithText("Automate Your Sleep Environment").assertIsDisplayed()
        composeRule.onNodeWithText("Create Your First Rule").assertIsDisplayed()
        composeRule.onNodeWithText("Restless Motion Vibration Pulse").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun savedRuleShowsHumanReadableConditionAndConfirmationOnDelete() {
        val testRule = SmartRule(
            id = "test-rule-1",
            name = "Gentle Waking Pulse",
            enabled = true,
            source = RuleSource.ML_CLASS,
            operator = RuleOperator.EQUALS,
            threshold = null,
            targetClass = "ACTIVE",
            action = RuleAction.VIBRATION_PULSE,
            cooldownMillis = 5_000L,
            requiredConsecutiveMatches = 3,
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        var deleted = false

        composeRule.setContent {
            SleepTrackerTheme {
                SmartScenariosScreen(
                    state = SmartScenariosUiState(rules = listOf(testRule)),
                    onSave = {},
                    onEnabled = { _, _ -> },
                    onDelete = { deleted = true },
                )
            }
        }

        composeRule.onNodeWithText("Gentle Waking Pulse").assertIsDisplayed()
        composeRule.onNodeWithText("When ML predicts ACTIVE").assertIsDisplayed()

        // Click delete to trigger confirmation dialog
        composeRule.onNodeWithText("Delete").performClick()

        // Confirmation dialog must be shown
        composeRule.onNodeWithText("Delete Automation Rule?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete Rule").performClick()

        assert(deleted)
    }

    @Test
    fun clickingNewRuleOpensRuleEditorDialog() {
        composeRule.setContent {
            SleepTrackerTheme {
                SmartScenariosScreen(
                    state = SmartScenariosUiState(),
                    onSave = {},
                    onEnabled = { _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("New Rule").performClick()

        composeRule.onNodeWithText("Create Automation Rule").assertIsDisplayed()
        composeRule.onNodeWithText("ML State").assertIsDisplayed()
        composeRule.onNodeWithText("Accelerometer").assertIsDisplayed()
    }
}
