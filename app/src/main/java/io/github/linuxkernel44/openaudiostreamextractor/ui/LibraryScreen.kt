package io.github.linuxkernel44.openaudiostreamextractor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.linuxkernel44.openaudiostreamextractor.MainViewModel
import io.github.linuxkernel44.openaudiostreamextractor.data.StationEntity
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stations by vm.library.collectAsState()
    val query by vm.libraryQuery.collectAsState()
    val favOnly by vm.favoritesOnly.collectAsState()
    var renaming by remember { mutableStateOf<StationEntity?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf("json") }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) scope.launch {
            val text = vm.exportNow(pendingFormat)
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
                .onSuccess { vm.message = "Exported." }.onFailure { vm.message = "Export failed." }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            if (text != null) vm.importText(text) else vm.message = "Could not read that file."
        }
    }

    Column(modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileUpload, "Import") }
            IconButton(onClick = { exportMenu = true }) { Icon(Icons.Filled.FileDownload, "Export") }
            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                listOf("m3u" to "Export as M3U", "pls" to "Export as PLS", "json" to "Export as JSON").forEach { (f, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        exportMenu = false
                        pendingFormat = f
                        exporter.launch("stations.$f")
                    })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.libraryQuery.value = it }, singleLine = true,
                label = { Text("Search") }, modifier = Modifier.weight(1f)
            )
            FilterChip(selected = favOnly, onClick = { vm.favoritesOnly.value = !favOnly }, label = { Text("Favorites") })
        }
        if (stations.isEmpty()) {
            Text("No saved stations yet. Extract a stream and tap the bookmark icon.", modifier = Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(stations, key = { it.id }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOfNotNull(s.codec, s.bitrateKbps?.let { "$it kbps" }, s.kind).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.toggleFavorite(s) }) {
                                Icon(if (s.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite")
                            }
                        }
                        Text(s.streamUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row {
                            IconButton(onClick = { copyToClipboard(ctx, s.streamUrl) }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                            IconButton(onClick = { shareText(ctx, s.streamUrl) }) { Icon(Icons.Filled.Share, "Share") }
                            if (s.id in vm.recheckingIds) {
                                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = { vm.recheck(s) }, enabled = s.pageUrl.isNotBlank() || true) { Icon(Icons.Filled.Refresh, "Re-check") }
                            }
                            IconButton(onClick = { renaming = s }) { Icon(Icons.Filled.Edit, "Rename") }
                            IconButton(onClick = { vm.delete(s) }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { st ->
        var name by remember(st.id) { mutableStateOf(st.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename station") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(st, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
}
