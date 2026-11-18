package io.github.linuxkernel44.openaudiostreamextractor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.linuxkernel44.openaudiostreamextractor.EngineStatus
import io.github.linuxkernel44.openaudiostreamextractor.MainViewModel
import io.github.linuxkernel44.openaudiostreamextractor.extract.StreamInfo

@Composable
fun ExtractScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var showOthers by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Audio Stream Extractor", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Paste a webradio page and get its live stream URL.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(
                value = vm.urlInput,
                onValueChange = { vm.urlInput = it },
                label = { Text("Webradio page URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.extract() }),
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { vm.urlInput = it.trim() } }) {
                        Icon(Icons.Filled.ContentPaste, "Paste")
                    }
                }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.extract() }, enabled = !vm.running && vm.urlInput.isNotBlank()) { Text("Extract") }
                if (vm.running) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
            }
        }
        if (vm.engineOrderShown.isNotEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Engines", style = MaterialTheme.typography.titleSmall)
                        vm.engineOrderShown.forEach { id ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                when (val s = vm.engineStatus[id]) {
                                    EngineStatus.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    is EngineStatus.Finished -> when {
                                        s.error != null -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                                        s.valid > 0 -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                        else -> Icon(Icons.Filled.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.outline)
                                    }
                                    null -> Icon(Icons.Filled.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.outlineVariant)
                                }
                                Column {
                                    Text(id.label, style = MaterialTheme.typography.bodyMedium)
                                    val detail = when (val s = vm.engineStatus[id]) {
                                        EngineStatus.Running -> "Running…"
                                        is EngineStatus.Finished -> s.error ?: "${s.raw} candidate(s), ${s.valid} valid"
                                        null -> if (vm.running) "Waiting" else "Skipped"
                                    }
                                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        vm.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.tertiary) } }

        if (vm.done && vm.results.isEmpty()) {
            item {
                Text(
                    "No live stream found. Try another engine order in Settings, or open the page in the Browser tab, start playback and tap Extract.",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (vm.results.isNotEmpty()) {
            item { Text("Best match", style = MaterialTheme.typography.titleMedium) }
            item { StreamCard(vm.results.first(), vm, highlighted = true) }
            val others = vm.results.drop(1)
            if (others.isNotEmpty()) {
                item {
                    TextButton(onClick = { showOthers = !showOthers }) {
                        Icon(if (showOthers) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        Text("Other candidates (${others.size})")
                    }
                }
                if (showOthers) items(others) { StreamCard(it, vm, highlighted = false) }
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
fun StreamCard(s: StreamInfo, vm: MainViewModel, highlighted: Boolean) {
    val ctx = LocalContext.current
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = if (highlighted) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.elevatedCardColors()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.stationName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            SelectionContainer { Text(s.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(s.kind.label) })
                s.codec?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                s.bitrateKbps?.let { AssistChip(onClick = {}, label = { Text("$it kbps") }) }
            }
            Text("Found by ${s.engine.label} · score ${s.score}${if (s.isLive) " · live" else ""}", style = MaterialTheme.typography.labelSmall)
            Row {
                IconButton(onClick = { copyToClipboard(ctx, s.url) }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                IconButton(onClick = { shareText(ctx, s.url) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { vm.save(s) }) { Icon(Icons.Filled.BookmarkAdd, "Save to library") }
            }
        }
    }
}
