package com.batterycast.quant.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricLabelStyle
import com.batterycast.quant.core.ui.theme.MetricSmallStyle
import com.batterycast.quant.core.ui.theme.MetricStyle

/*
 * Three surface levels, and only three.
 *
 * v1 gave every block of content the same card: same background, same radius, same padding, same
 * weight. The result was that a probability, an explanation and a privacy footer all looked equally
 * important. Here the hierarchy is structural — the hero is a gradient with a tinted glow, ordinary
 * content is a flat card, and explanatory content has no container at all.
 */

/**
 * Level 1. Reserved for the single most important thing on a screen.
 *
 * A soft diagonal gradient plus a tone-tinted glow behind the content, so the surface itself
 * carries the forecast's mood without turning the whole screen green or red.
 */
@Composable
fun HeroSurface(
    modifier: Modifier = Modifier,
    glow: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val semantic = BatteryCastTheme.semanticColors
    val animatedGlow by animateColorAsState(
        targetValue = glow ?: Color.Transparent,
        animationSpec = tween(600),
        label = "heroGlow",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(listOf(semantic.heroSurfaceTop, semantic.heroSurfaceBottom)),
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(animatedGlow.copy(alpha = 0.16f), Color.Transparent),
                    radius = 720f,
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(30.dp)),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * Level 2. Ordinary content that deserves a container but not the spotlight.
 */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * Level 3. Explanatory or secondary content, on the background with no chrome at all.
 *
 * Removing the card from prose is the cheapest way to make the real content look important.
 */
@Composable
fun QuietBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** The small uppercase caption that introduces a metric or a group. */
@Composable
fun MetricLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text.uppercase(),
        style = MetricLabelStyle,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A section heading on the background, sized well below the page title. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A metric with its label beneath, at roughly a 3:1 size ratio.
 *
 * This replaces v1's label-left / value-right rows, where a median and a caveat carried identical
 * visual weight.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    emphasised: Boolean = false,
    supporting: String? = null,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: $value"
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = if (emphasised) MetricStyle else MetricSmallStyle,
            color = valueColor,
            maxLines = 1,
        )
        MetricLabel(label)
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A compact row: 56–72dp, no container, divider-free.
 *
 * Used wherever v1 would have spent a whole card on one number.
 */
@Composable
fun CompactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
            maxLines = 1,
        )
    }
}

/**
 * A navigable row with a tinted icon disc and a chevron.
 *
 * Replaces v1's five near-identical full cards on the More screen, which were three lines tall
 * each with a small icon lost at the left.
 */
@Composable
fun NavigationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Inline status, not a badge.
 *
 * v1 put "Preliminary live estimate" in an amber pill at the top of the hero, where it competed
 * with the headline number and won. A quiet line with a dot reads as context rather than warning.
 */
@Composable
fun InlineStatus(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A slim probability meter used in lists, where a full ring would be excessive.
 */
@Composable
fun ProbabilityMeter(
    probability: Double,
    modifier: Modifier = Modifier,
    color: Color = BatteryCastTheme.semanticColors.forProbability(probability),
) {
    val animated by animateFloatAsState(
        targetValue = probability.toFloat().coerceIn(0f, 1f),
        animationSpec = tween(650),
        label = "meter",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(6.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
