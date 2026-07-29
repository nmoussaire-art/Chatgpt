package com.deadlineguardian.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deadlineguardian.data.Deadline
import com.deadlineguardian.data.ItemKind
import com.deadlineguardian.data.TrackedItem
import com.deadlineguardian.engine.ProposedDeadline
import com.deadlineguardian.engine.ScanResult
import com.deadlineguardian.ui.GuardianViewModel
import com.deadlineguardian.ui.ScanState
import com.deadlineguardian.ui.Urgent
import com.deadlineguardian.ui.displayName
import com.deadlineguardian.ui.formatMoney
import com.deadlineguardian.ui.pretty
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: GuardianViewModel,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.scanState.collectAsStateWithLifecycle()

    // Held across recomposition so the camera result can find the file it wrote to.
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.scan(it) } }

    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) cameraUri?.let { viewModel.scan(it) } }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = newCaptureUri(context)
            cameraUri = uri
            camera.launch(uri)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Scan") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.resetScan(); onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val s = state) {
                is ScanState.Idle -> SourcePicker(
                    onCamera = { cameraPermission.launch(android.Manifest.permission.CAMERA) },
                    onGallery = {
                        gallery.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )

                is ScanState.Working -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(14.dp))
                    Text("Reading the text…", style = MaterialTheme.typography.bodyMedium)
                }

                is ScanState.Failed -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Couldn't read that", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = { viewModel.resetScan() }) { Text("Try again") }
                }

                is ScanState.Ready -> ReviewPane(
                    result = s.result,
                    imagePath = s.imagePath,
                    onCancel = { viewModel.resetScan() },
                    onSave = { item, deadlines ->
                        viewModel.confirmScan(item, deadlines) { onDone() }
                    }
                )
            }
        }
    }
}

@Composable
private fun SourcePicker(onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("What are we tracking?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Get the whole thing in frame, with the dates visible. " +
                "Reading happens on this phone — the photo never leaves it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(26.dp))
        Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Take a photo")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Choose from gallery")
        }
    }
}

/**
 * The review step is deliberately not skippable. OCR on a crumpled receipt is never
 * perfect, and a wrong date that the user never saw is worse than no reminder at all —
 * so every proposed deadline shows its reasoning and can be unticked before saving.
 */
@Composable
private fun ReviewPane(
    result: ScanResult,
    imagePath: String?,
    onCancel: () -> Unit,
    onSave: (TrackedItem, List<Deadline>) -> Unit
) {
    var title by remember { mutableStateOf(result.title) }
    var kind by remember { mutableStateOf(result.kind) }
    val accepted = remember {
        mutableStateMapOf<Int, Boolean>().apply {
            result.proposals.indices.forEach { put(it, true) }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Here's what I found", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (result.confidence >= 0.5f) {
                    "Looks like a ${kind.displayName().lowercase()}. Check the dates below."
                } else {
                    "I'm not certain what this is — please confirm the type and dates."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text(
                "TYPE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ItemKind.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kind = k },
                                label = { Text(k.displayName()) }
                            )
                        }
                    }
                }
            }
        }

        if (result.amountMinor != null || result.purchaseDate != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        result.purchaseDate?.let {
                            DetailRow("Purchased", it.pretty())
                        }
                        result.amountMinor?.let {
                            DetailRow("Amount", formatMoney(it, result.currency))
                        }
                        result.merchant?.let { DetailRow("Merchant", it) }
                    }
                }
            }
        }

        item {
            Text(
                "DEADLINES TO TRACK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (result.proposals.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No usable date found on this one. Try a sharper photo, or save it " +
                            "and add a date by hand from the item screen.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        result.proposals.forEachIndexed { index, proposal ->
            item(key = "proposal-$index") {
                ProposalCard(
                    proposal = proposal,
                    currency = result.currency,
                    checked = accepted[index] ?: true,
                    onCheckedChange = { accepted[index] = it }
                )
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    val item = TrackedItem(
                        title = title.ifBlank { kind.displayName() },
                        kind = kind,
                        merchant = result.merchant,
                        purchaseDate = result.purchaseDate,
                        amountMinor = result.amountMinor,
                        currency = result.currency,
                        imagePath = imagePath,
                        rawText = result.rawText
                    )
                    val deadlines = result.proposals
                        .filterIndexed { i, _ -> accepted[i] == true }
                        .map { p ->
                            Deadline(
                                itemId = 0,
                                kind = p.kind,
                                date = p.date,
                                leadDays = p.leadDays,
                                moneyAtRiskMinor = p.moneyAtRiskMinor
                            )
                        }
                    onSave(item, deadlines)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                val n = accepted.count { it.value }
                Text(if (n == 0) "Save without reminders" else "Track $n deadline${if (n > 1) "s" else ""}")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Rescan")
            }
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: ProposedDeadline,
    currency: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 6.dp, top = 10.dp, end = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    proposal.kind.displayName(),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    proposal.date.pretty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                proposal.moneyAtRiskMinor?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${formatMoney(it, currency)} recoverable",
                        style = MaterialTheme.typography.bodySmall,
                        color = Urgent.Red,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(5.dp))
                // Showing the reasoning is what makes an assumed date safe to trust.
                Text(
                    proposal.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (proposal.assumed) Urgent.Amber
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun newCaptureUri(context: android.content.Context): Uri {
    val dir = File(context.filesDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
