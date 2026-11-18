package io.github.linuxkernel44.openaudiostreamextractor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.linuxkernel44.openaudiostreamextractor.extract.EngineId
import io.github.linuxkernel44.openaudiostreamextractor.extract.ExtractionSettings
import io.github.linuxkernel44.openaudiostreamextractor.extract.RunMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val kMode = stringPreferencesKey("mode")
    private val kOrder = stringPreferencesKey("order")
    private val kEnabled = stringPreferencesKey("enabled")
    private val kTimeout = intPreferencesKey("timeout")

    val settings: Flow<ExtractionSettings> = context.dataStore.data.map { p ->
        val mode = p[kMode]?.let { runCatching { RunMode.valueOf(it) }.getOrNull() } ?: RunMode.SEQUENTIAL
        val order = p[kOrder]?.split(',')?.mapNotNull { runCatching { EngineId.valueOf(it) }.getOrNull() }
            ?.let { saved -> saved + EngineId.entries.filter { it !in saved } } ?: EngineId.defaultOrder
        val enabled = p[kEnabled]?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { runCatching { EngineId.valueOf(it) }.getOrNull() }?.toSet()
            ?: EngineId.entries.toSet()
        ExtractionSettings(mode, order, enabled, p[kTimeout] ?: 25)
    }

    suspend fun update(transform: (ExtractionSettings) -> ExtractionSettings) {
        context.dataStore.edit { p ->
            val current = ExtractionSettings(
                p[kMode]?.let { runCatching { RunMode.valueOf(it) }.getOrNull() } ?: RunMode.SEQUENTIAL,
                p[kOrder]?.split(',')?.mapNotNull { runCatching { EngineId.valueOf(it) }.getOrNull() }
                    ?.let { saved -> saved + EngineId.entries.filter { it !in saved } } ?: EngineId.defaultOrder,
                p[kEnabled]?.split(',')?.filter { it.isNotBlank() }?.mapNotNull { runCatching { EngineId.valueOf(it) }.getOrNull() }?.toSet()
                    ?: EngineId.entries.toSet(),
                p[kTimeout] ?: 25
            )
            val n = transform(current)
            p[kMode] = n.mode.name
            p[kOrder] = n.customOrder.joinToString(",") { it.name }
            p[kEnabled] = n.enabled.joinToString(",") { it.name }
            p[kTimeout] = n.timeoutSeconds
        }
    }
}
