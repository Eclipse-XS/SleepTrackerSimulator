package com.example.sleeptrackersimulator.ui.insights

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.example.sleeptrackersimulator.ui.theme.SleepTrackerTheme
import org.junit.Rule
import org.junit.Test

class InsightsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun demoModeDisplaysPopulatedSyntheticMetricsAndTrend() {
        composeRule.setContent {
            SleepTrackerTheme {
                InsightsScreen(
                    state = InsightsUiState(
                        selectedMode = InsightsMode.DEMO,
                        demoMetrics = InsightsMetrics(
                            sessionCount = 90,
                            averageDurationMs = 8 * 3_600_000L,
                            averageLowMotionFraction = 0.78f,
                            averageMovementEpisodes = 14f,
                            averageHeartRateBpm = 63f,
                            averageCoverageFraction = 0.94f,
                        ),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Demo (90)").assertIsDisplayed()
        composeRule.onNodeWithText("90 session(s)").assertIsDisplayed()
        composeRule.onNodeWithText("Nightly low-motion trend").performScrollTo().assertIsDisplayed()
    }

    @Test fun oneRealSessionShowsDescriptionButNotBaselineClaim() {
        composeRule.setContent {
            SleepTrackerTheme {
                InsightsScreen(
                    state = InsightsUiState(
                        selectedMode = InsightsMode.REAL,
                        realMetrics = InsightsMetrics(sessionCount = 1, averageDurationMs = 7 * 3_600_000L, averageLowMotionFraction = 0.8f),
                        comparableSessionCount = 1,
                        isQualityGateSatisfied = false,
                    ),
                )
            }
        }
        composeRule.onNodeWithText("1 session(s)").assertIsDisplayed()
        composeRule.onNodeWithText("Personal baseline in progress").performScrollTo().assertIsDisplayed()
    }
}
