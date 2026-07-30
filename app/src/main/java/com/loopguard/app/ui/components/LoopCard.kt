package com.loopguard.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopguard.app.data.Side
import com.loopguard.app.ui.ScoredLoop
import java.time.LocalDate

/**
 * The card that carries the whole product.
 *
 * Reading order is deliberate: what it is, who owns it, how much time is left,
 * then the two actions that actually resolve it.
 */
@Composable
fun LoopCard(
    item: ScoredLoop,
    today: LocalDate,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    showActions: Boolean = true,
    onFollowUp: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    onSnooze: (() -> Unit)? = null,
) {
    val loop = item.loop
    val priority = item.priority
    val accent = bandColour(priority.band)
    val snoozed = snoozeLabel(loop, today)

    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {

            // Pressure spine: colour is the fastest signal on the screen.
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(accent),
            )

            Column(Modifier.padding(16.dp)) {

                Row(verticalAlignment = Alignment.Top) {
                    if (rank != null) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$rank",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(11.dp))
                    }

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (loop.pinned) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                loop.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            loop.whoLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.width(10.dp))
                    PriorityRing(
                        score = priority.score,
                        band = priority.band,
                        diameter = 46,
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        text = if (loop.sideEnum == Side.ME) "Your move" else "Their move",
                        container = if (loop.sideEnum == Side.ME) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        content = if (loop.sideEnum == Side.ME) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Pill(
                        text = snoozed ?: statusLine(loop, today),
                        container = if (snoozed != null) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            bandContainer(priority.band)
                        },
                        content = if (snoozed != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Pill(text = loop.category)
                }

                if (showActions) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onFollowUp != null) {
                            OutlinedButton(
                                onClick = onFollowUp,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 14.dp, vertical = 6.dp,
                                ),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (loop.sideEnum == Side.ME) "Next step" else "Follow up",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        if (onComplete != null) {
                            TextButton(onClick = onComplete) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("Done", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (onSnooze != null) {
                            TextButton(onClick = onSnooze) {
                                Icon(
                                    Icons.Default.Snooze,
                                    contentDescription = "Snooze",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
