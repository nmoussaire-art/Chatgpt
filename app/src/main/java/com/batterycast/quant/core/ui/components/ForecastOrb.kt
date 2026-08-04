package com.batterycast.quant.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.HeroValueStyle
import com.batterycast.quant.core.ui.theme.MetricLabelStyle
import com.batterycast.quant.core.ui.theme.RiskTone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The app's visual signature.
 *
 * One glyph that answers both halves of the question at once:
 *
 * - **Where you are.** The bright arc is the battery you have now.
 * - **Where you are going.** The translucent arc is the charge the forecast expects you to spend
 *   before your target, so the bright arc's *end* is what you are predicted to have left.
 * - **How sure that is.** The faint outer arc spans the 10th-to-90th-percentile range. A wide halo
 *   is the app saying, visually, that it does not know precisely — which is far more honest and far
 *   quicker to read than a printed interval.
 * - **What counts as failure.** A tick at the reserve level gives the arc something to be measured
 *   against.
 *
 * Everything is drawn from real simulated paths; nothing here is decorative.
 */
@Composable
fun ForecastOrb(
    currentPercent: Double,
    predictedPercent: Double?,
    lowPercent: Double?,
    highPercent: Double?,
    reservePercent: Double,
    tone: RiskTone,
    modifier: Modifier = Modifier,
    isCharging: Boolean = false,
    caption: String? = null,
) {
    val semantic = BatteryCastTheme.semanticColors
    val toneColor = semantic.forTone(tone)
    val accent = if (isCharging) semantic.healthy else toneColor

    val animatedCurrent by animateFloatAsState(
        targetValue = currentPercent.toFloat().coerceIn(0f, 100f),
        animationSpec = tween(900),
        label = "orbCurrent",
    )
    val animatedPredicted by animateFloatAsState(
        targetValue = (predictedPercent ?: currentPercent).toFloat().coerceIn(0f, 100f),
        animationSpec = tween(900),
        label = "orbPredicted",
    )
    val animatedLow by animateFloatAsState(
        targetValue = (lowPercent ?: predictedPercent ?: currentPercent).toFloat().coerceIn(0f, 100f),
        animationSpec = tween(900),
        label = "orbLow",
    )
    val animatedHigh by animateFloatAsState(
        targetValue = (highPercent ?: predictedPercent ?: currentPercent).toFloat().coerceIn(0f, 100f),
        animationSpec = tween(900),
        label = "orbHigh",
    )

    val description = buildString {
        append("Battery now ${currentPercent.roundToInt()} percent. ")
        if (predictedPercent != null) {
            append("Predicted ${predictedPercent.roundToInt()} percent at your target")
            if (lowPercent != null && highPercent != null) {
                append(", likely between ${lowPercent.roundToInt()} and ${highPercent.roundToInt()} percent")
            }
            append(". ")
        }
        append("Reserve level ${reservePercent.roundToInt()} percent.")
    }

    Box(
        modifier = modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .aspectRatio(1.08f)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.08f)) {
            drawOrb(
                current = animatedCurrent,
                predicted = animatedPredicted,
                low = animatedLow,
                high = animatedHigh,
                reserve = reservePercent.toFloat(),
                accent = accent,
                trackColor = semantic.ringTrack,
                spendColor = accent.copy(alpha = 0.22f),
                haloColor = accent.copy(alpha = 0.13f),
                thresholdColor = semantic.chartThreshold,
                showHalo = lowPercent != null && highPercent != null,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Text(
                text = "${animatedCurrent.roundToInt()}%",
                style = HeroValueStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = if (isCharging) "CHARGING" else "NOW",
                style = MetricLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Sweep geometry: a 270° arc with the gap at the bottom, so 0 % and 100 % are visually distinct. */
private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f

private fun angleFor(percent: Float): Float = START_ANGLE + SWEEP_ANGLE * (percent.coerceIn(0f, 100f) / 100f)

private fun DrawScope.drawOrb(
    current: Float,
    predicted: Float,
    low: Float,
    high: Float,
    reserve: Float,
    accent: Color,
    trackColor: Color,
    spendColor: Color,
    haloColor: Color,
    thresholdColor: Color,
    showHalo: Boolean,
) {
    val stroke = size.minDimension * 0.075f
    val haloStroke = size.minDimension * 0.032f
    val inset = stroke / 2f + size.minDimension * 0.06f
    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
    val topLeft = Offset(inset, inset)

    // 1. Track — the whole scale, so the filled part has something to be a fraction of.
    drawArc(
        color = trackColor,
        startAngle = START_ANGLE,
        sweepAngle = SWEEP_ANGLE,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )

    // 2. Uncertainty halo, outside the ring: the 10th-to-90th percentile of where the battery
    //    lands at the target. Width *is* the message.
    if (showHalo && high > low) {
        val haloInset = inset - stroke * 0.72f
        drawArc(
            color = haloColor,
            startAngle = angleFor(low),
            sweepAngle = SWEEP_ANGLE * ((high - low) / 100f),
            useCenter = false,
            topLeft = Offset(haloInset, haloInset),
            size = Size(size.width - haloInset * 2, size.height - haloInset * 2),
            style = Stroke(width = haloStroke, cap = StrokeCap.Round),
        )
    }

    // 3. The charge expected to be spent between now and the target: translucent, so the eye reads
    //    the bright arc's end as "what I will have left".
    if (current > predicted) {
        drawArc(
            color = spendColor,
            startAngle = angleFor(predicted),
            sweepAngle = SWEEP_ANGLE * ((current - predicted) / 100f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
    }

    // 4. The charge that is predicted to remain — a gradient so the ring has depth rather than
    //    reading as a flat progress bar.
    val keep = minOf(current, predicted).coerceAtLeast(0f)
    if (keep > 0f) {
        drawArc(
            brush = Brush.sweepGradient(
                0f to accent.copy(alpha = 0.55f),
                0.35f to accent,
                1f to accent.copy(alpha = 0.55f),
            ),
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE * (keep / 100f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }

    // 5. Reserve tick — the level below which the forecast counts as a failure.
    val radius = arcSize.minDimension / 2f
    val centre = Offset(size.width / 2f, size.height / 2f)
    val reserveRadians = Math.toRadians(angleFor(reserve).toDouble())
    val tickOuter = radius + stroke * 0.75f
    val tickInner = radius - stroke * 0.75f
    drawLine(
        color = thresholdColor,
        start = Offset(
            centre.x + (cos(reserveRadians) * tickInner).toFloat(),
            centre.y + (sin(reserveRadians) * tickInner).toFloat(),
        ),
        end = Offset(
            centre.x + (cos(reserveRadians) * tickOuter).toFloat(),
            centre.y + (sin(reserveRadians) * tickOuter).toFloat(),
        ),
        strokeWidth = size.minDimension * 0.008f,
        cap = StrokeCap.Round,
    )

    // 6. A dot at the predicted level, so the target has a precise point rather than an edge.
    if (predicted in 0.5f..99.5f) {
        val predictedRadians = Math.toRadians(angleFor(predicted).toDouble())
        val dotCentre = Offset(
            centre.x + (cos(predictedRadians) * radius).toFloat(),
            centre.y + (sin(predictedRadians) * radius).toFloat(),
        )
        drawCircle(color = accent.copy(alpha = 0.28f), radius = stroke * 0.62f, center = dotCentre)
        drawCircle(color = accent, radius = stroke * 0.30f, center = dotCentre)
    }
}

/**
 * A miniature of the ring, for list rows and comparisons where the full orb would dominate.
 */
@Composable
fun MiniRing(
    percent: Double,
    tone: RiskTone,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val semantic = BatteryCastTheme.semanticColors
    val color = semantic.forTone(tone)
    val animated by animateFloatAsState(
        targetValue = percent.toFloat().coerceIn(0f, 100f),
        animationSpec = tween(600),
        label = "miniRing",
    )

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = size.minDimension * 0.14f
            val inset = stroke / 2f
            drawArc(
                color = semantic.ringTrack,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE * (animated / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${animated.roundToInt()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
