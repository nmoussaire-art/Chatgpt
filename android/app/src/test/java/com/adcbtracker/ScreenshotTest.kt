package com.adcbtracker

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.parser.ParsedTransaction
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/** Renders the real screens with sample data so the UI can be reviewed without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val out = "build/screenshots"

    @Before
    fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<App>()
        app.repo.populateDefaultCategoriesIfNeeded()
        val merchants = listOf(
            "ADNOC SHAMS 310 BK" to "ABUDHABI-AE", "CARREFOUR MCC" to "ABU DHABI-AE", "TALABAT" to "DUBAI-AE",
            "MAX" to "DUBAI-AE", "UPCO MINIBOUNCE ALRE" to "ABU DHABI-AE", "STARBUCKS" to "ABU DHABI-AE",
            "AMAZON.AE" to "DUBAI-AE", "NOON" to "DUBAI-AE",
        )
        val rnd = Random(7)
        val today = LocalDate.now(UAE_ZONE)
        for (d in 0..70) {
            val date = today.minusDays(d.toLong())
            repeat(rnd.nextInt(0, 5)) { i ->
                val (m, loc) = merchants[rnd.nextInt(merchants.size)]
                val amount = (rnd.nextInt(800, 45000)).toLong()
                app.repo.ingestParsed(
                    "sms",
                    ParsedTransaction("XX1332", amount, "AED", m, loc, date.atTime(LocalTime.of(9 + i * 3, rnd.nextInt(60))), 5_000_000L, "seed $d $i"),
                )
            }
        }
        app.repo.mapMerchantToCategory("ADNOC SHAMS 310 BK", 2)
        app.repo.mapMerchantToCategory("CARREFOUR MCC", 5)
        app.repo.mapMerchantToCategory("TALABAT", 1)
        app.repo.mapMerchantToCategory("STARBUCKS", 1)
        app.repo.mapMerchantToCategory("MAX", 3)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        captureScreenRoboImage("$out/$name.png")
    }

    @Test
    fun homeAndDaySheet() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Daily spending").fetchSemanticsNodes().isNotEmpty() }
        shot("1_home")
        compose.onAllNodesWithText("Yesterday").onFirst().performClick()
        shot("2_home_day_sheet")
    }

    @Test
    fun insights() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Daily spending").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Insights").onFirst().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Spending calendar").fetchSemanticsNodes().isNotEmpty() }
        shot("3_insights")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("By day of week"))
        shot("3b_insights_charts")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Cycle history"))
        shot("3c_insights_history")
    }

    @Test
    fun otherTabs() {
        compose.waitUntil(5000) { compose.onAllNodesWithText("Daily spending").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Transactions").onFirst().performClick()
        shot("4_transactions")
        compose.onAllNodesWithText("Categories").onFirst().performClick()
        shot("5_categories")
        compose.onAllNodesWithText("Settings").onFirst().performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Billing cycle"))
        shot("6_settings")
    }
}
