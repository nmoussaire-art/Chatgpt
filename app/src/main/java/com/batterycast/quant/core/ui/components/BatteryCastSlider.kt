package com.batterycast.quant.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.theme.BatteryCastTheme

/**
 * The app's slider.
 *
 * Material's default draws a dotted tick for every step, which on an eighteen-step control is a row
 * of dots that reads as texture rather than as information — and it competes with the value the
 * user is actually setting. This replaces it with a solid capsule rail, a bright filled active
 * portion, and a round thumb that swells slightly and picks up a halo while it is being dragged, so
 * the touch target confirms itself under a finger that is covering it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryCastSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    accent: Color = MaterialTheme.colorScheme.primary,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val semantic = BatteryCastTheme.semanticColors
    val interactionSource = remember { MutableInteractionSource() }
    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val active = dragged || pressed

    val thumbScale by animateFloatAsState(
        targetValue = if (active) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "sliderThumbScale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (active) 0.22f else 0f,
        animationSpec = spring(),
        label = "sliderThumbGlow",
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        thumb = {
            Box(
                modifier = Modifier.size(THUMB_TOUCH_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                if (glowAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .size(THUMB_TOUCH_SIZE)
                            .scale(thumbScale)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = glowAlpha)),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(THUMB_SIZE)
                        .scale(thumbScale)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span <= 0f) {
                0f
            } else {
                ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT)
                    .clip(CircleShape)
                    .background(semantic.trackInactive),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        },
    )
}

private val TRACK_HEIGHT = 6.dp
private val THUMB_SIZE = 20.dp

/** The thumb's touch target, and the extent of its halo while dragging. */
private val THUMB_TOUCH_SIZE = 28.dp
