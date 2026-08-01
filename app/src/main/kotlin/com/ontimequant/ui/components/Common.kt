package com.ontimequant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.PersonalizationLevel
import com.ontimequant.model.RiskLevel
import com.ontimequant.ui.format.glyph
import com.ontimequant.ui.format.label
import com.ontimequant.ui.theme.LocalReducedMotion
import com.ontimequant.ui.theme.LocalRiskColors

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (title != null || trailing != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        title?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium)
                        }
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

/**
 * Risk is shown as glyph + word + colour together. Colour alone would fail for a
 * colour-blind user, and the glyph alone would be cryptic; every one of the three carries
 * the full meaning on its own.
 */
@Composable
fun RiskBadge(level: RiskLevel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val risk = LocalRiskColors.current
    val (fg, bg) = when (level) {
        RiskLevel.LOW -> risk.low to risk.lowContainer
        RiskLevel.MODERATE -> risk.moderate to risk.moderateContainer
        RiskLevel.ELEVATED -> risk.elevated to risk.elevatedContainer
        RiskLevel.HIGH -> risk.high to risk.highContainer
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "${level.label()} traffic risk" },
        color = bg,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(level.glyph(), color = fg, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Text(
                if (compact) level.label() else "${level.label()} risk",
                color = fg,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Small label saying where a number came from. Never omitted for demo or cached data. */
@Composable
fun ProvenanceChip(provenance: DataProvenance, modifier: Modifier = Modifier, text: String? = null) {
    val risk = LocalRiskColors.current
    val colour = when (provenance) {
        DataProvenance.LIVE -> risk.low
        DataProvenance.LEARNED -> MaterialTheme.colorScheme.primary
        DataProvenance.CACHED, DataProvenance.DEMO -> risk.elevated
        DataProvenance.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        DataProvenance.DEFAULT, DataProvenance.USER_DEFINED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colour.copy(alpha = 0.14f))
            .border(1.dp, colour.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            (text ?: provenance.label()).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colour,
        )
    }
}

@Composable
fun PersonalizationChip(level: PersonalizationLevel, modifier: Modifier = Modifier) {
    val filled = when (level) {
        PersonalizationLevel.PRELIMINARY -> 1
        PersonalizationLevel.LEARNING -> 2
        PersonalizationLevel.PARTIALLY_PERSONALIZED -> 3
        PersonalizationLevel.PERSONALIZED -> 4
    }
    Row(
        modifier.semantics {
            contentDescription = "Forecast personalisation: ${level.label}, level $filled of 4"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { index ->
            Box(
                Modifier
                    .padding(end = 3.dp)
                    .size(width = 12.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            level.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A labelled statistic. Used everywhere; keeps number/label pairing consistent. */
@Composable
fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = "$label: $value" + (supporting?.let { ". $it" } ?: "")
        },
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
        supporting?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(20.dp))
            it()
        }
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** An honest inline notice: degraded mode, demo data, missing provider. */
@Composable
fun NoticeBanner(
    message: String,
    provenance: DataProvenance,
    modifier: Modifier = Modifier,
) {
    val risk = LocalRiskColors.current
    val colour = when (provenance) {
        DataProvenance.DEMO, DataProvenance.CACHED -> risk.elevated
        DataProvenance.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colour.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            ProvenanceChip(provenance)
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Animated horizontal probability bar, honest about being an approximation of a number. */
@Composable
fun ProbabilityBar(
    probability: Double,
    target: Double,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val risk = LocalRiskColors.current
    val reduced = LocalReducedMotion.current
    val animated by animateFloatAsState(
        targetValue = probability.toFloat().coerceIn(0f, 1f),
        animationSpec = tween(if (reduced) 0 else 550),
        label = "probability",
    )
    val colour = if (probability >= target) risk.low else if (probability >= target - 0.10) risk.elevated else risk.high
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(colour),
        )
    }
}
