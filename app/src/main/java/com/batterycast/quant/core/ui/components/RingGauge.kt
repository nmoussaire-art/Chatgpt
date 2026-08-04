package com.batterycast.quant.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.HeadlineProbabilityStyle
import com.batterycast.quant.core.ui.theme.MetricLabelStyle

/**
 * The hero's arc gauge.
 *
 * A 260° sweep with the gap at the bottom, so 0 % and 100 % are visually distinct and the caption
 * has somewhere to sit. The arc's colour is the confidence colour, which means the ring answers
 * "how worried should I be" before the number underneath it has been read at all.
 *
 * When [uncertaintyLow] and [uncertaintyHigh] are supplied, a faint outer arc spans that interval.
 * Its *width* is the message: a wide halo says the model does not know precisely, which is far
 * quicker to read than a printed range and much harder to overlook.
 */
@Composable
fun RingGauge(
    fraction: Float,
    caption: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 168.dp,
    valueText: String? = null,
    uncertaintyLow: Float? = null,
    uncertaintyHigh: Float? = null,
    contentDescription: String? = null,
) {
    val semantic = BatteryCastTheme.semanticColors
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(750),
        label = "ringGauge",
    )

    Box(
        modifier = modifier
            .size(diameter)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = size.minDimension * 0.085f
            val haloStroke = size.minDimension * 0.028f
            val inset = stroke / 2f + size.minDimension * 0.05f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            // 1. The rail, so the filled arc has something to be a fraction of.
            drawArc(
                color = semantic.trackInactive,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // 2. The uncertainty halo, outside the rail.
            if (uncertaintyLow != null && uncertaintyHigh != null && uncertaintyHigh > uncertaintyLow) {
                val haloInset = (inset - stroke * 0.80f).coerceAtLeast(haloStroke)
                val low = uncertaintyLow.coerceIn(0f, 1f)
                val high = uncertaintyHigh.coerceIn(0f, 1f)
                drawArc(
                    color = color.copy(alpha = 0.20f),
                    startAngle = START_ANGLE + SWEEP_ANGLE * low,
                    sweepAngle = SWEEP_ANGLE * (high - low),
                    useCenter = false,
                    topLeft = Offset(haloInset, haloInset),
                    size = Size(size.width - haloInset * 2, size.height - haloInset * 2),
                    style = Stroke(width = haloStroke, cap = StrokeCap.Round),
                )
            }

            // 3. The value itself.
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            // Just enough inset to keep the text off the arc. Any more and "100%" at a large
            // display size no longer fits on one line inside the ring.
            modifier = Modifier.padding(horizontal = diameter * 0.10f),
        ) {
            Text(
                text = valueText ?: "${(animated * 100).toInt()}%",
                style = HeadlineProbabilityStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = caption.uppercase(),
                style = MetricLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** A miniature of the ring, for comparisons where the full gauge would dominate. */
@Composable
fun MiniRing(
    fraction: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
) {
    val semantic = BatteryCastTheme.semanticColors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "miniRing")

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = size.minDimension * 0.13f
            val inset = stroke / 2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = semantic.trackInactive,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    color = color,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Sweep geometry: the gap sits at the bottom, centred. */
private const val START_ANGLE = 140f
private const val SWEEP_ANGLE = 260f
