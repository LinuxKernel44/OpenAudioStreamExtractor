package io.github.linuxkernel44.openaudiostreamextractor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.linuxkernel44.openaudiostreamextractor.BuildConfig
import io.github.linuxkernel44.openaudiostreamextractor.MainViewModel
import io.github.linuxkernel44.openaudiostreamextractor.extract.EngineId
import io.github.linuxkernel44.openaudiostreamextractor.extract.RunMode

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    Column(
        modifier.statusBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))

        Text("Run mode", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            RunMode.entries.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = s.mode == m, role = Role.RadioButton, onClick = { vm.updateSettings { it.copy(mode = m) } }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    RadioButton(selected = s.mode == m, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                        Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Engines", style = MaterialTheme.typography.titleMedium)
        val canReorder = s.mode == RunMode.CUSTOM
        Text(
            if (canReorder) "Toggle engines and use the arrows to set their order." else "Toggle engines. Ordering is available in Custom order mode.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val list = if (canReorder) s.customOrder else EngineId.defaultOrder
        list.forEachIndexed { index, id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${id.label}", style = MaterialTheme.typography.bodyLarge)
                    Text(id.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canReorder) {
                    IconButton(enabled = index > 0, onClick = { vm.updateSettings { c -> c.copy(customOrder = c.customOrder.toMutableList().also { it.add(index - 1, it.removeAt(index)) }) } }) {
                        Icon(Icons.Filled.ArrowUpward, "Move up")
                    }
                    IconButton(enabled = index < list.lastIndex, onClick = { vm.updateSettings { c -> c.copy(customOrder = c.customOrder.toMutableList().also { it.add(index + 1, it.removeAt(index)) }) } }) {
                        Icon(Icons.Filled.ArrowDownward, "Move down")
                    }
                }
                Switch(checked = id in s.enabled, onCheckedChange = { on -> vm.updateSettings { c -> c.copy(enabled = if (on) c.enabled + id else c.enabled - id) } })
            }
        }

        HorizontalDivider()
        Text("Timeout per engine: ${s.timeoutSeconds} s", style = MaterialTheme.typography.titleMedium)
        Slider(value = s.timeoutSeconds.toFloat(), onValueChange = { v -> vm.updateSettings { it.copy(timeoutSeconds = v.toInt()) } }, valueRange = 10f..60f, steps = 9)

        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "Audio Stream Extractor ${BuildConfig.VERSION_NAME}\n" +
                "GPL-3.0-or-later. Includes uBlock Origin (GPL-3.0) and Mozilla GeckoView (MPL-2.0).\n" +
                "No analytics, no telemetry, no accounts. The app only contacts the sites and directories you ask it to inspect.",
            style = MaterialTheme.typography.bodySmall
        )
        Text("", modifier = Modifier.padding(24.dp))
    }
}
