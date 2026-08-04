package com.batterycast.quant.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricLabelStyle

/**
 * A horizontally scrolling row whose ends fade out.
 *
 * The row runs the full width of the display and carries the page gutter as its own content
 * padding, so a chip at the edge is partially visible rather than cut off at a card boundary. A
 * short gradient scrim over each end makes that partial chip read as "there is more this way"
 * instead of as a clipping bug — and it does so without a scrollbar, which on a five-item row is
 * more chrome than information.
 *
 * The scrim is only painted on the side that can actually be scrolled towards, so a row that fits
 * on screen shows no fade at all.
 */
@Composable
fun EdgeFadedRow(
    modifier: Modifier = Modifier,
    gutter: Dp = 16.dp,
    itemSpacing: Dp = 8.dp,
    fadeColor: Color = MaterialTheme.colorScheme.background,
    fadeWidth: Dp = 24.dp,
    content: LazyListScope.() -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val canScrollBackward by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf { listState.canScrollBackward }
    }
    val canScrollForward by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf { listState.canScrollForward }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            content = content,
        )

        if (canScrollBackward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(fadeWidth)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(fadeColor, Color.Transparent))),
            )
        }
        if (canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(fadeWidth)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, fadeColor))),
            )
        }
    }
}

/**
 * A selectable chip carrying a name and a supporting value on two lines.
 *
 * Two lines rather than one is what stops the truncation: "Midnight · 12:00 AM" on a single line is
 * wide enough to clip at a large display size, whereas the same content stacked stays comfortably
 * inside a chip at any font scale. Both lines are single-line by construction, so neither can wrap.
 */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val semantic = BatteryCastTheme.semanticColors
    val shape = MaterialTheme.shapes.small
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
        tween(200),
        label = "chipContainer",
    )
    val border by animateColorAsState(
        if (selected) accent.copy(alpha = 0.55f) else semantic.cardStroke,
        tween(200),
        label = "chipBorder",
    )
    val label by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onSurface,
        tween(200),
        label = "chipLabel",
    )

    Column(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = label,
            maxLines = 1,
            softWrap = false,
        )
        if (supporting != null) {
            Text(
                text = supporting.uppercase(),
                style = MetricLabelStyle,
                color = if (selected) accent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** A single-line variant, for choices that carry no secondary value. */
@Composable
fun CompactChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val semantic = BatteryCastTheme.semanticColors
    val shape = MaterialTheme.shapes.small
    val container by animateColorAsState(
        if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
        tween(200),
        label = "compactChipContainer",
    )
    val border by animateColorAsState(
        if (selected) accent.copy(alpha = 0.55f) else semantic.cardStroke,
        tween(200),
        label = "compactChipBorder",
    )
    val label by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onSurface,
        tween(200),
        label = "compactChipLabel",
    )

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = label,
            maxLines = 1,
            softWrap = false,
        )
    }
}
