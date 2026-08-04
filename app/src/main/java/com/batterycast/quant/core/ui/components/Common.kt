package com.batterycast.quant.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.theme.BadgeStyle
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricLabelStyle
import com.batterycast.quant.core.ui.theme.MetricValueStyle
import com.batterycast.quant.core.ui.theme.MonospaceNumberStyle

/**
 * The app's standard panel.
 *
 * Elevation is expressed as one step of value plus a hairline stroke, never as a shadow. On an OLED
 * canvas a drop shadow has nothing to fall on — it just muddies the edge — whereas a 1px stroke at
 * 8 % white reads as a crisp boundary at any brightness.
 */
@Composable
fun BatteryCastCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BatteryCastTheme.semanticColors.cardStroke, shape),
        shape = shape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A labelled value in a row.
 *
 * The value is set in tabular figures, so a column of them stays aligned and a live number does not
 * shuffle its neighbours every time it ticks.
 */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    supporting: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MonospaceNumberStyle,
            color = valueColor,
        )
    }
}

/**
 * A metric with its caption beneath, for the hero's micro-grid.
 *
 * Value above label, not label above value: the number is what is being read, and putting it first
 * lets the eye scan a row of three without stopping on the captions.
 */
@Composable
fun MetricTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MetricValueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label.uppercase(),
            style = MetricLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small pill. Used for confidence, data maturity, charging state and scenario provenance. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
    leading: String? = null,
) {
    val animatedColor by animateColorAsState(color, tween(300), label = "chipColor")
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(animatedColor.copy(alpha = 0.12f))
            .border(1.dp, animatedColor.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (leading != null) {
            Text(text = leading, style = BadgeStyle, color = animatedColor)
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedColor,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text.uppercase(),
            style = BadgeStyle,
            color = animatedColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The honest empty state.
 *
 * Shown instead of a forecast whenever the evidence for one does not exist. It says what the app is
 * doing and what will unlock next, and it never renders a placeholder chart behind a message.
 */
@Composable
fun CollectingDataCard(
    headline: String,
    detail: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    progressLabel: String? = null,
) {
    BatteryCastCard(modifier = modifier) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "collectProgress")
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = BatteryCastTheme.semanticColors.caution,
                trackColor = BatteryCastTheme.semanticColors.trackInactive,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            if (progressLabel != null) {
                Text(
                    text = progressLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A horizontal probability bar.
 *
 * Colour follows the same thresholds as the wording, so the bar and the sentence never disagree,
 * and the trailing figure is tabular so a refreshing list does not jitter.
 */
@Composable
fun ProbabilityBar(
    probability: Double,
    modifier: Modifier = Modifier,
    label: String? = null,
    trailing: String? = null,
    leadingIcon: String? = null,
) {
    val semantic = BatteryCastTheme.semanticColors
    val color = semantic.forProbability(probability)
    val animated by animateFloatAsState(
        probability.toFloat().coerceIn(0f, 1f),
        tween(500),
        label = "probabilityBar",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    if (label != null) append("$label: ")
                    append("${(probability * 100).toInt()} percent")
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label != null || trailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (label != null) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (leadingIcon != null) {
                            Text(text = leadingIcon, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(text = trailing, style = MonospaceNumberStyle, color = color)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(semantic.trackInactive),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * A span on a timeline, drawn as a bar rather than printed as three timestamps.
 *
 * Used for threshold crossings: [fraction] is where the median lands across the forecast window,
 * and [lowFraction]..[highFraction] is the interval around it. Seeing the intervals for 20 %, 10 %
 * and 5 % stacked makes it immediately obvious that each is wider than the last — which is the
 * whole point, and is invisible in a column of times.
 */
@Composable
fun RangeBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    lowFraction: Float? = null,
    highFraction: Float? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    label: String? = null,
    value: String? = null,
    leadingIcon: String? = null,
) {
    val semantic = BatteryCastTheme.semanticColors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "rangeBar")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label != null || value != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (label != null) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (leadingIcon != null) {
                            Text(text = leadingIcon, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (value != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(text = value, style = MonospaceNumberStyle, color = color)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(semantic.trackInactive),
        ) {
            // The interval, painted first so the median marker sits on top of it.
            val low = lowFraction?.coerceIn(0f, 1f)
            val high = highFraction?.coerceIn(0f, 1f)
            if (low != null && high != null && high > low) {
                FractionalSpan(start = low, end = high) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.30f)),
                    )
                }
            }
            // The median, as a tick rather than a fill: the bar is a position on a timeline, not a
            // quantity, so filling it from the left would read as "how much", which it is not.
            FractionalSpan(
                start = (animated - MEDIAN_TICK_HALF_WIDTH).coerceIn(0f, 1f - 2 * MEDIAN_TICK_HALF_WIDTH),
                end = (animated + MEDIAN_TICK_HALF_WIDTH).coerceIn(2 * MEDIAN_TICK_HALF_WIDTH, 1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

/**
 * Places [content] across the horizontal span [start]..[end] of its parent, in fractions of width.
 *
 * Compose has no "start at fraction x" modifier, so the span is expressed as weighted spacers,
 * which is exact at any width and needs no measurement pass of its own.
 */
@Composable
private fun FractionalSpan(
    start: Float,
    end: Float,
    content: @Composable () -> Unit,
) {
    val leading = start.coerceIn(0f, 1f)
    val span = (end - start).coerceIn(MIN_SPAN, 1f)
    val trailing = (1f - leading - span).coerceAtLeast(0f)

    Row(modifier = Modifier.fillMaxSize()) {
        if (leading > 0f) Spacer(Modifier.weight(leading))
        Box(modifier = Modifier.weight(span).fillMaxHeight()) { content() }
        if (trailing > 0f) Spacer(Modifier.weight(trailing))
    }
}

/** Half the width of the median tick, as a fraction of the bar. */
private const val MEDIAN_TICK_HALF_WIDTH = 0.012f

/** A weight of zero is illegal, so a degenerate span still gets a hairline. */
private const val MIN_SPAN = 0.004f

/** Footer line stating where the numbers came from and how fresh they are. */
@Composable
fun DataFreshnessFooter(
    text: String,
    modifier: Modifier = Modifier,
    isStale: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isStale) {
            BatteryCastTheme.semanticColors.caution
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.clearAndSetSemantics { contentDescription = text },
    )
}

/** A thin separator, at the same weight as a panel's own stroke. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(BatteryCastTheme.semanticColors.cardStroke),
    )
}
