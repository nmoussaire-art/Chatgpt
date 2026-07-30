package com.loopguard.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.loopguard.app.data.Loop
import com.loopguard.app.domain.FollowUpComposer
import com.loopguard.app.domain.Priority
import com.loopguard.app.domain.Tone
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowUpScreen(
    loop: Loop,
    priority: Priority,
    senderName: String,
    today: LocalDate,
    onBack: () -> Unit,
    onSent: (Tone) -> Unit,
    onNotify: (String) -> Unit,
) {
    val suggested = remember(loop.id) { FollowUpComposer.suggestTone(loop, priority) }
    var tone by remember(loop.id) { mutableStateOf(suggested) }
    var message by remember(loop.id) { mutableStateOf("") }
    var edited by remember(loop.id) { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Regenerate when the tone changes, unless the user has taken over the text.
    LaunchedEffect(tone) {
        if (!edited) {
            message = FollowUpComposer.compose(loop, tone, senderName, today)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Follow-up") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(loop.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(3.dp))
            Text(
                "To ${loop.whoLabel}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "TONE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Tone.entries.toList()) { option ->
                    FilterChip(
                        selected = tone == option,
                        onClick = { tone = option; edited = false },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(13.dp)) {
                    Text(
                        tone.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tone == suggested) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Suggested for this loop, based on ${loop.followUpCount} previous " +
                                if (loop.followUpCount == 1) "follow-up." else "follow-ups.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it; edited = true },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Message") },
                minLines = 10,
                shape = RoundedCornerShape(16.dp),
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, loop.title)
                            putExtra(Intent.EXTRA_TEXT, message)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "Send follow-up"))
                            onSent(tone)
                        }.onFailure { onNotify("No app available to share this.") }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Send")
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(message))
                        onSent(tone)
                        onNotify("Copied. Logged as a follow-up.")
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(17.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Sending or copying records a follow-up on the timeline and resets the silence clock, " +
                    "so the next reminder is measured from today.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
