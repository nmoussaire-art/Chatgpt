package com.loopguard.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopguard.app.data.Loop
import com.loopguard.app.data.Side
import com.loopguard.app.domain.PriorityBand
import com.loopguard.app.domain.PriorityEngine
import com.loopguard.app.ui.theme.LocalLoopGuardColors
import java.time.LocalDate

@Composable
fun bandColour(band: PriorityBand): Color {
    val c = LocalLoopGuardColors.current
    return when (band) {
        PriorityBand.CRITICAL -> c.critical
        PriorityBand.HIGH -> c.high
        PriorityBand.MEDIUM -> c.medium
        PriorityBand.LOW -> c.calm
    }
}

@Composable
fun bandContainer(band: PriorityBand): Color {
    val c = LocalLoopGuardColors.current
    return when (band) {
        PriorityBand.CRITICAL -> c.criticalContainer
        PriorityBand.HIGH -> c.highContainer
        PriorityBand.MEDIUM -> c.mediumContainer
        PriorityBand.LOW -> c.calmContainer
    }
}

/** Animated ring used for the pressure gauge and per-loop scores. */
@Composable
fun PriorityRing(
    score: Int,
    band: PriorityBand,
    modifier: Modifier = Modifier,
    diameter: Int = 68,
    label: String? = null,
) {
    val target = (score.coerceIn(0, 100)) / 100f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(700),
        label = "ring",
    )
    val colour by animateColorAsState(bandColour(band), tween(500), label = "ringColour")
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .size(diameter.dp)
            .semantics { contentDescription = "Priority score $score out of 100, ${band.label}" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(diameter.dp)) {
            val strokeWidth = diameter * 0.13f
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = colour,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.titleMedium,
                fontSize = (diameter * 0.28).sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (diameter * 0.13).sp,
                )
            }
        }
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier.semantics { contentDescription = "$value $label" },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
fun TagRow(tags: List<String>, onTagClick: (String) -> Unit = {}) {
    if (tags.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.take(4).forEach { tag ->
            Pill(
                text = "#$tag",
                container = Color.Transparent,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(50),
                ),
                onClick = { onTagClick(tag) },
            )
        }
    }
}

// ---------------------------------------------------------------------
// Human-readable status text
// ---------------------------------------------------------------------

/** "4 days overdue" / "Due today" / "Quiet for 6 days". */
fun statusLine(loop: Loop, today: LocalDate): String {
    val days = PriorityEngine.daysUntilDue(loop, today)
    val quiet = PriorityEngine.daysSilent(loop, today)

    val deadline = when {
        days == null -> null
        days < 0 -> "${-days} ${plural(-days, "day")} overdue"
        days == 0 -> "Due today"
        days == 1 -> "Due tomorrow"
        days <= 14 -> "Due in $days days"
        else -> "Due ${loop.due}"
    }

    val silence = when {
        loop.sideEnum != Side.THEM -> null
        quiet == 0 -> null
        quiet == 1 -> "quiet 1 day"
        else -> "quiet $quiet days"
    }

    return listOfNotNull(deadline, silence).joinToString(" · ").ifBlank { "No deadline" }
}

fun snoozeLabel(loop: Loop, today: LocalDate): String? {
    val until = loop.snoozedUntilDate ?: return null
    if (!until.isAfter(today)) return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, until)
    return if (days == 1L) "Back tomorrow" else "Back in $days days"
}

fun impactLabel(impact: Int): String = when (impact.coerceIn(1, 5)) {
    5 -> "Critical"
    4 -> "High"
    3 -> "Medium"
    2 -> "Low"
    else -> "Minimal"
}

private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
