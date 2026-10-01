package com.adcbtracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adcbtracker.data.Category
import com.adcbtracker.data.TransactionWithCategory
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.ui.theme.Amber
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink1
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.Ink3
import com.adcbtracker.ui.theme.TextMid
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatMoney(minor: Long, currency: String = "AED"): String {
    val sign = if (minor < 0) "-" else ""
    val abs = kotlin.math.abs(minor)
    val whole = String.format(Locale.US, "%,d", abs / 100)
    return "$currency $sign$whole.${"%02d".format(abs % 100)}"
}

/** Compact form for tight spaces, e.g. "1.2k". */
fun formatCompact(minor: Long): String {
    val v = minor / 100.0
    return when {
        v >= 10_000 -> String.format(Locale.US, "%.0fk", v / 1000)
        v >= 1_000 -> String.format(Locale.US, "%.1fk", v / 1000)
        else -> String.format(Locale.US, "%.0f", v)
    }
}

fun parseColor(hex: String?, fallback: Color = TextMid): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

fun relativeDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> if (date.year == today.year) date.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.US))
    else date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US))
}

fun timeOf(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(UAE_ZONE).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink1)
            .border(1.dp, Ink2, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMid)
        }
    }
}

@Composable
fun CategoryBadge(category: Category?, size: Int = 40) {
    val color = parseColor(category?.colorHex, Ink3)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(category?.icon ?: "💳", fontSize = (size * 0.45f).sp)
    }
}

@Composable
fun Pill(text: String, color: Color = Emerald, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** One transaction as a compact row: badge, merchant, time/category, amount. */
@Composable
fun TransactionRow(
    item: TransactionWithCategory,
    largeThresholdMinor: Long,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tx = item.tx
    val isLarge = tx.amountMinor >= largeThresholdMinor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(item.category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.merchant ?: "Unknown Merchant",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val whenText = if (showDate) {
                Instant.ofEpochMilli(tx.tsEpochMillis).atZone(UAE_ZONE)
                    .format(DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.US))
            } else timeOf(tx.tsEpochMillis)
            val sub = listOfNotNull(whenText, item.category?.name ?: "Uncategorized", tx.location).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatMoney(tx.amountMinor, tx.currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (tx.amountMinor < 0) Emerald else MaterialTheme.colorScheme.onSurface,
            )
            if (isLarge) Text("⚠️ Large", style = MaterialTheme.typography.labelSmall, color = Amber)
        }
    }
}

@Composable
fun EmptyState(emoji: String, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 44.sp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextMid)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(Ink2)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}
