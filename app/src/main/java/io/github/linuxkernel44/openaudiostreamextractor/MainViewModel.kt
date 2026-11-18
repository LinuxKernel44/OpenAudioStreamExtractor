package io.github.linuxkernel44.openaudiostreamextractor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linuxkernel44.openaudiostreamextractor.data.PlaylistIO
import io.github.linuxkernel44.openaudiostreamextractor.data.StationEntity
import io.github.linuxkernel44.openaudiostreamextractor.extract.EngineId
import io.github.linuxkernel44.openaudiostreamextractor.extract.ExtractionEvent
import io.github.linuxkernel44.openaudiostreamextractor.extract.ExtractionOrchestrator
import io.github.linuxkernel44.openaudiostreamextractor.extract.ExtractionSettings
import io.github.linuxkernel44.openaudiostreamextractor.extract.RawCandidate
import io.github.linuxkernel44.openaudiostreamextractor.extract.StreamInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EngineStatus {
    data object Running : EngineStatus
    data class Finished(val raw: Int, val valid: Int, val error: String?) : EngineStatus
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as OaseApp).container
    private val dao = container.db.stations()

    var urlInput by mutableStateOf("")
    var running by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    var results by mutableStateOf<List<StreamInfo>>(emptyList())
        private set
    var lastPageUrl by mutableStateOf("")
        private set
    val engineStatus = mutableStateMapOf<EngineId, EngineStatus>()
    val engineOrderShown = mutableStateListOf<EngineId>()
    var message by mutableStateOf<String?>(null)

    val settings = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ExtractionSettings())

    val libraryQuery = MutableStateFlow("")
    val favoritesOnly = MutableStateFlow(false)
    val library = kotlinx.coroutines.flow.combine(libraryQuery, favoritesOnly) { q, f -> q to f }
        .flatMapLatest { (q, f) -> dao.observe(q.trim(), f) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recheckingIds = mutableStateListOf<Long>()

    private var job: Job? = null

    fun extract(url: String = urlInput) {
        if (url.isBlank() || running) return
        urlInput = url
        lastPageUrl = ExtractionOrchestrator.normalizeUrl(url)
        results = emptyList()
        done = false
        message = null
        engineStatus.clear()
        job = viewModelScope.launch {
            running = true
            val s = settings.value
            engineOrderShown.clear()
            engineOrderShown.addAll(s.activeEngines())
            if (engineOrderShown.isEmpty()) {
                message = "All engines are disabled. Enable at least one in Settings."
                running = false
                return@launch
            }
            try {
                container.orchestrator.run(url, s).collect { e ->
                    when (e) {
                        is ExtractionEvent.EngineStarted -> engineStatus[e.engine] = EngineStatus.Running
                        is ExtractionEvent.EngineFinished -> engineStatus[e.engine] = EngineStatus.Finished(e.rawCount, e.validCount, e.error)
                        is ExtractionEvent.Done -> {
                            results = e.streams
                            done = true
                        }
                    }
                }
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
        running = false
    }

    fun save(stream: StreamInfo, pageUrl: String = lastPageUrl) {
        viewModelScope.launch {
            if (dao.findByStream(stream.url) != null) {
                message = "Already in your library."
                return@launch
            }
            val name = stream.stationName ?: runCatching { java.net.URI(pageUrl).host }.getOrNull() ?: "Station"
            dao.insert(
                StationEntity(
                    name = name, pageUrl = pageUrl, streamUrl = stream.url, codec = stream.codec,
                    bitrateKbps = stream.bitrateKbps, kind = stream.kind.label
                )
            )
            message = "Saved to library."
        }
    }

    fun rename(s: StationEntity, name: String) = viewModelScope.launch { dao.update(s.copy(name = name.ifBlank { s.name })) }
    fun toggleFavorite(s: StationEntity) = viewModelScope.launch { dao.update(s.copy(favorite = !s.favorite)) }
    fun delete(s: StationEntity) = viewModelScope.launch { dao.delete(s.id) }

    /** Re-runs extraction for a saved station and swaps in the best result. */
    fun recheck(s: StationEntity) {
        if (s.id in recheckingIds) return
        viewModelScope.launch {
            recheckingIds += s.id
            try {
                // A saved stream that still validates needs no re-extraction.
                val still = container.validator.validate(RawCandidate(s.streamUrl, EngineId.KNOWN_API))
                if (still.isNotEmpty()) {
                    dao.update(s.copy(lastCheckedAt = System.currentTimeMillis()))
                    message = "“${s.name}” is still working."
                    return@launch
                }
                var best: StreamInfo? = null
                container.orchestrator.run(s.pageUrl, settings.value).collect { e ->
                    if (e is ExtractionEvent.Done) best = e.streams.firstOrNull()
                }
                best?.let {
                    dao.update(s.copy(streamUrl = it.url, codec = it.codec, bitrateKbps = it.bitrateKbps, kind = it.kind.label, lastCheckedAt = System.currentTimeMillis()))
                    message = "“${s.name}” updated with a new stream URL."
                } ?: run { message = "Could not find a working stream for “${s.name}”." }
            } finally {
                recheckingIds -= s.id
            }
        }
    }

    fun updateSettings(transform: (ExtractionSettings) -> ExtractionSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    fun export(format: String): suspend () -> String = {
        val entries = PlaylistIO.toEntries(dao.all())
        when (format) {
            "m3u" -> PlaylistIO.toM3u(entries)
            "pls" -> PlaylistIO.toPls(entries)
            else -> PlaylistIO.toJson(entries)
        }
    }

    fun importText(text: String) {
        viewModelScope.launch {
            val entries = runCatching { PlaylistIO.parseAny(text) }.getOrElse {
                message = "Could not read that file."
                return@launch
            }
            var added = 0
            entries.forEach { e ->
                if (dao.findByStream(e.streamUrl) == null) {
                    dao.insert(StationEntity(name = e.name, pageUrl = e.pageUrl, streamUrl = e.streamUrl, codec = e.codec, bitrateKbps = e.bitrateKbps, favorite = e.favorite))
                    added++
                }
            }
            message = "Imported $added station(s)."
        }
    }

    suspend fun exportNow(format: String): String = export(format)()
}
