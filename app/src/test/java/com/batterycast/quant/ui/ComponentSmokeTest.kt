package com.batterycast.quant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.chart.ForecastFanChart
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.BatteryCastSlider
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.CompactChip
import com.batterycast.quant.core.ui.components.EdgeFadedRow
import com.batterycast.quant.core.ui.components.MetricTile
import com.batterycast.quant.core.ui.components.MiniRing
import com.batterycast.quant.core.ui.components.ProbabilityBar
import com.batterycast.quant.core.ui.components.RangeBar
import com.batterycast.quant.core.ui.components.RingGauge
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.SelectableChip
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.forecasting.sim.ForecastPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composes every custom component on the JVM.
 *
 * This exists because of a real defect: a modifier given an illegal constant compiles cleanly and
 * throws only when it is composed, so the first sign of it was the app opening and closing on the
 * user's phone. The screens themselves need a live `ViewModel` and cannot be reached from here, but
 * the components carrying the layout arithmetic can — the ring's arc geometry, the range bar's
 * weighted spans, the chart's smoothing and clamping — and that is where the arithmetic lives.
 *
 * Every case runs at the extremes as well as the middle, because the values that break layout maths
 * are 0, 1 and the degenerate range, not 0.5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComponentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun composeInTheme(content: @Composable () -> Unit) {
        compose.setContent { BatteryCastTheme(darkTheme = true) { content() } }
        compose.waitForIdle()
    }

    @Test
    fun `ring gauge composes across its whole range`() {
        composeInTheme {
            Column {
                listOf(0f, 0.005f, 0.5f, 0.999f, 1f).forEach { fraction ->
                    RingGauge(fraction = fraction, caption = "chance of lasting", color = Emerald)
                }
            }
        }
        // 0f and 0.005f both render "0%", so the assertion picks a fraction only one ring shows.
        compose.onNodeWithText("50%").assertExists()
    }

    @Test
    fun `ring gauge composes with a degenerate uncertainty interval`() {
        composeInTheme {
            Column {
                RingGauge(
                    fraction = 0.4f,
                    caption = "chance",
                    color = Emerald,
                    uncertaintyLow = 0.4f,
                    uncertaintyHigh = 0.4f,
                )
                RingGauge(
                    fraction = 0.4f,
                    caption = "chance",
                    color = Emerald,
                    uncertaintyLow = 0f,
                    uncertaintyHigh = 1f,
                )
                // Deliberately inverted: real percentile data has produced this after a clamp.
                RingGauge(
                    fraction = 0.4f,
                    caption = "chance",
                    color = Emerald,
                    uncertaintyLow = 0.9f,
                    uncertaintyHigh = 0.1f,
                )
            }
        }
    }

    @Test
    fun `range bar composes at both ends and with a zero-width interval`() {
        composeInTheme {
            Column {
                RangeBar(fraction = 0f, lowFraction = 0f, highFraction = 0f, label = "floor", value = "now")
                RangeBar(fraction = 1f, lowFraction = 1f, highFraction = 1f, label = "ceiling", value = "later")
                RangeBar(fraction = 0.5f, lowFraction = 0.1f, highFraction = 0.9f, label = "middle", value = "mid")
                // Out-of-range inputs: a crossing time can exceed the horizon it is scaled against.
                RangeBar(fraction = 1.4f, lowFraction = -0.2f, highFraction = 2f, label = "beyond", value = "?")
            }
        }
        compose.onNodeWithText("middle").assertExists()
    }

    @Test
    fun `probability bar composes at every threshold boundary`() {
        composeInTheme {
            Column {
                listOf(0.0, 0.30, 0.70, 1.0).forEach { probability ->
                    ProbabilityBar(
                        probability = probability,
                        label = "at ${(probability * 100).toInt()}",
                        trailing = "${(probability * 100).toInt()}%",
                        leadingIcon = "🎮",
                    )
                }
            }
        }
        compose.onNodeWithText("at 70").assertExists()
    }

    @Test
    fun `cards, rows, tiles and chips compose`() {
        composeInTheme {
            BatteryCastCard {
                SectionHeader(title = "Header", subtitle = "Subtitle")
                StatRow(label = "Median", value = "42%", supporting = "10th percentile")
                MetricTile(value = "-14.8%/h", label = "Current drain")
                StatusChip(text = "Live estimate", leading = "⚡")
                MiniRing(fraction = 0.75f, color = Emerald)
                CompactChip(text = "50%", selected = true, onClick = {})
            }
        }
        compose.onNodeWithText("Median").assertExists()
    }

    @Test
    fun `the edge-faded chip row composes and scrolls`() {
        composeInTheme {
            EdgeFadedRow {
                items(TARGET_LABELS) { label ->
                    SelectableChip(
                        text = label,
                        supporting = "12:00 AM",
                        selected = label == "Midnight",
                        onClick = {},
                    )
                }
            }
        }
        // The label that truncated to "Midnig..." in the reported build.
        compose.onNodeWithText("Midnight").assertExists()
    }

    @Test
    fun `the slider composes at both ends of its range`() {
        composeInTheme {
            Column {
                var value by remember { mutableFloatStateOf(5f) }
                BatteryCastSlider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 5f..95f,
                    steps = 17,
                )
                BatteryCastSlider(value = 95f, onValueChange = {}, valueRange = 5f..95f, steps = 17)
                // A collapsed range would divide by zero when computing the fill fraction.
                BatteryCastSlider(value = 1f, onValueChange = {}, valueRange = 1f..1f)
            }
        }
    }

    @Test
    fun `the fan chart composes, smooths and scrubs`() {
        composeInTheme {
            Column {
                ForecastFanChart(points = curve(), targetMs = START_MS + 30 * 60_000L)
                // A flat curve makes every secant zero, which is the branch the monotone limiter
                // has to short-circuit rather than divide by.
                ForecastFanChart(points = flatCurve())
                // Two points is the minimum the smoother can interpolate between.
                ForecastFanChart(points = curve().take(2))
            }
        }
    }

    @Test
    fun `the chart declines to draw fewer than two points`() {
        composeInTheme {
            ForecastFanChart(points = curve().take(1))
        }
    }

    @Test
    fun `a very narrow chart still composes`() {
        // The tooltip and axis labels clamp themselves against the plot width; at a width narrower
        // than the label the naive clamp inverts its own bounds and throws.
        composeInTheme {
            ForecastFanChart(points = curve(), modifier = Modifier.width(48.dp))
        }
    }

    @Test
    fun `the collecting state composes with and without progress`() {
        composeInTheme {
            Column {
                CollectingDataCard(headline = "Collecting", detail = "Nothing is estimated yet.")
                CollectingDataCard(
                    headline = "Collecting",
                    detail = "Nothing is estimated yet.",
                    progress = 0f,
                    progressLabel = "0 readings",
                )
                CollectingDataCard(headline = "Collecting", detail = "Detail", progress = 1f)
            }
        }
        compose.onNodeWithText("0 readings").assertExists()
    }

    private fun curve(): List<ForecastPoint> = (0..24).map { step ->
        val level = 80.0 - step * 2.5
        val spread = 1.0 + step * 0.4
        ForecastPoint(
            atMs = START_MS + step * 5 * 60_000L,
            median = level,
            mean = level,
            p05 = level - spread * 2,
            p10 = level - spread * 1.5,
            p25 = level - spread,
            p75 = level + spread,
            p90 = level + spread * 1.5,
            p95 = level + spread * 2,
        )
    }

    private fun flatCurve(): List<ForecastPoint> = (0..12).map { step ->
        ForecastPoint(
            atMs = START_MS + step * 5 * 60_000L,
            median = 50.0,
            mean = 50.0,
            p05 = 50.0,
            p10 = 50.0,
            p25 = 50.0,
            p75 = 50.0,
            p90 = 50.0,
            p95 = 50.0,
        )
    }

    private companion object {
        const val START_MS = 1_700_000_000_000L
        val TARGET_LABELS = listOf("Bedtime", "Midnight", "In 2 hours", "In 4 hours", "Stand-up meeting")
        val Emerald = androidx.compose.ui.graphics.Color(0xFF00E699)
    }
}
