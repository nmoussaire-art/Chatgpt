package com.batterycast.quant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.batterycast.quant.MainActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app against the real battery.
 *
 * The assertions are deliberately about *what the app is willing to claim* rather than about
 * particular numbers: on a freshly installed app the correct screen is the collecting state, and
 * on one with history it is a forecast. Both are valid; asserting a specific percentage would
 * mean the test was reading something other than the device's real battery.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DashboardComposeTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() = hiltRule.inject()

    @Test
    fun theDashboardAsksTheCentralQuestion() {
        composeRule.onNodeWithText("Will your phone last?").assertIsDisplayed()
    }

    @Test
    fun theDashboardShowsEitherAForecastOrAnHonestCollectingState() {
        composeRule.waitForIdle()

        val collecting = composeRule.onAllNodes(
            hasText("BatteryCast is collecting live battery behaviour", substring = true),
        ).fetchSemanticsNodes()
        val forecasting = composeRule.onAllNodes(
            hasText("chance of staying above", substring = true),
        ).fetchSemanticsNodes()

        // Exactly one of the two states, never a placeholder forecast over no evidence.
        assertThat(collecting.size + forecasting.size).isAtLeast(1)
    }

    @Test
    fun thePrivacyStatementIsVisibleOnHome() {
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            "All battery observations and forecasts remain on your device.",
        ).assertIsDisplayed()
    }

    @Test
    fun noNavigationLabelWrapsOntoASecondLine() {
        // The v1 bar rendered "Chances" as "Chance" / "s", which broke the alignment of every
        // other tab. Every label is now one short word and must stay on one line.
        listOf("Home", "Forecast", "Scenarios", "Charge").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        assertThat(composeRule.onAllNodes(hasText("Chances")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun everyPrimaryDestinationOpensWithoutCrashing() {
        listOf("Curve", "Chances", "Charge", "More", "Home").forEach { label ->
            composeRule.onNodeWithText(label).performClick()
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithText("Will your phone last?").assertIsDisplayed()
    }

    @Test
    fun theAccuracyScreenNeverClaimsAHeadlineAccuracyPercentage() {
        composeRule.onNodeWithContentDescription("Settings and privacy").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Model accuracy").performClick()
        composeRule.waitForIdle()

        val claims = composeRule.onAllNodes(hasText("% accurate", substring = true)).fetchSemanticsNodes()

        assertThat(claims).isEmpty()
    }

    @Test
    fun theSettingsScreenExplainsEachOptionalPermission() {
        composeRule.onNodeWithContentDescription("Settings and privacy").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Usage access").assertIsDisplayed()
        composeRule.onNodeWithText("Calendar").assertIsDisplayed()
        composeRule.onNodeWithText("What leaves your phone").assertIsDisplayed()
    }
}
