package com.batterycast.quant.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.theme.BatteryCastTheme

/**
 * The app's standard card.
 *
 * Generous padding and a soft container colour rather than heavy elevation: the design brief is
 * "calm instrument", and stacked drop shadows read as busy.
 */
@Composable
fun BatteryCastCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A labelled value in a row, used throughout the detail cards. */
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
        )
    }
}

/** A small pill. Used for confidence, data maturity, and scenario provenance. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
) {
    val animatedColor by animateColorAsState(color, tween(300), label = "chipColor")
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(animatedColor.copy(alpha = 0.14f))
            .border(1.dp, animatedColor.copy(alpha = 0.32f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = animatedColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The honest empty state.
 *
 * Shown instead of a forecast whenever the evidence for one does not exist. It says what the app
 * is doing and what will unlock next, and it never renders a placeholder chart behind a message.
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
                    .clip(RoundedCornerShape(3.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
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
 * Colour follows the same thresholds as the wording, so the bar and the sentence never disagree.
 */
@Composable
fun ProbabilityBar(
    probability: Double,
    modifier: Modifier = Modifier,
    label: String? = null,
    trailing: String? = null,
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
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (trailing != null) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

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
