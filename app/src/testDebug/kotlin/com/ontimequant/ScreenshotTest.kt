package com.ontimequant

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.ontimequant.data.prefs.ThemeMode
import com.ontimequant.data.prefs.UserSettings
import com.ontimequant.ui.accuracy.AccuracyPreviewContent
import com.ontimequant.ui.curve.CurveScreen
import com.ontimequant.ui.details.DetailsScreen
import com.ontimequant.ui.home.HomeScreen
import com.ontimequant.ui.insights.InsightsPreviewContent
import com.ontimequant.ui.preview.PreviewData
import com.ontimequant.ui.theme.OnTimeQuantTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens to PNG on the JVM.
 *
 * These are not mock-ups. Each screen is composed with [PreviewData], which runs the real
 * forecasting engine over the real demo scenario — the departure time, probabilities and
 * calibration figures in the images are the engine's own output. Regenerate with:
 *
 * ```
 * ./gradlew :app:recordRoborazziDebug
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h1100dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = UserSettings(use24HourClock = true, demoMode = true)

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            OnTimeQuantTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage(
            filePath = "screenshots/$name.png",
            roborazziOptions = RoborazziOptions(
                recordOptions = RoborazziOptions.RecordOptions(resizeScale = 1.0),
            ),
        )
    }

    @Test
    fun home_light() = capture("01-home-light") {
        HomeScreen(
            state = PreviewData.homeState(),
            onRecalculate = {}, onOpenCurve = {}, onOpenDetails = {},
            onSelectAppointment = {}, onNewAppointment = {}, onConfidenceChange = {},
            onNavigate = {}, onDeclareDeparted = {}, onConfirmArrival = {}, onCancelJourney = {},
        )
    }

    @Test
    fun home_dark() = capture("02-home-dark", dark = true) {
        HomeScreen(
            state = PreviewData.homeState(),
            onRecalculate = {}, onOpenCurve = {}, onOpenDetails = {},
            onSelectAppointment = {}, onNewAppointment = {}, onConfidenceChange = {},
            onNavigate = {}, onDeclareDeparted = {}, onConfirmArrival = {}, onCancelJourney = {},
        )
    }

    @Test
    fun departure_curve() = capture("03-departure-curve") {
        CurveScreen(
            forecast = PreviewData.forecast,
            settings = settings,
            now = PreviewData.now,
        )
    }

    @Test
    fun departure_curve_dark() = capture("04-departure-curve-dark", dark = true) {
        CurveScreen(
            forecast = PreviewData.forecast,
            settings = settings,
            now = PreviewData.now,
        )
    }

    @Test
    fun forecast_details() = capture("05-forecast-details") {
        DetailsScreen(forecast = PreviewData.forecast, settings = settings)
    }

    @Test
    fun model_accuracy() = capture("06-model-accuracy") {
        AccuracyPreviewContent(PreviewData.accuracy)
    }

    @Test
    fun model_insights() = capture("07-model-insights") {
        InsightsPreviewContent(PreviewData.insights, tripCount = 24)
    }
}
