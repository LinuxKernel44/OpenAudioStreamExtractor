package io.github.linuxkernel44.openaudiostreamextractor.extract

/** The extraction engines, in their default (speed-ordered) priority. */
enum class EngineId(val label: String, val description: String) {
    KNOWN_API("Known APIs", "Radio directories and Icecast/Shoutcast/Radio.co endpoints"),
    STATIC_PARSER("Static parser", "Fetches the page and scans HTML, scripts and iframes"),
    GECKO_NETWORK("Network interception", "Loads the page in GeckoView and watches network traffic"),
    GECKO_DOM("DOM injection", "Loads the page in GeckoView and presses play for you");

    companion object {
        val defaultOrder: List<EngineId> = entries.toList()
    }
}

enum class RunMode(val label: String, val description: String) {
    SEQUENTIAL("Sequential", "Engines run one after another in the default order and stop at the first validated stream."),
    PARALLEL("Parallel race", "All enabled engines run at once; the first validated streams win."),
    CUSTOM("Custom order", "Sequential, using the order you choose below.")
}

/** A URL produced by an engine that has not been validated yet. */
data class RawCandidate(
    val url: String,
    val engine: EngineId,
    val name: String? = null,
    val contentTypeHint: String? = null
)

enum class StreamKind(val label: String) {
    ICECAST("Icecast/Shoutcast"),
    HTTP_AUDIO("HTTP audio"),
    HLS("HLS"),
    ON_DEMAND("On-demand file")
}

/** A candidate that survived validation. */
data class StreamInfo(
    val url: String,
    val engine: EngineId,
    val kind: StreamKind,
    val contentType: String?,
    val codec: String?,
    val bitrateKbps: Int?,
    val stationName: String?,
    val genre: String?,
    val isLive: Boolean,
    val score: Int
)

sealed interface ExtractionEvent {
    data class EngineStarted(val engine: EngineId) : ExtractionEvent
    data class EngineFinished(
        val engine: EngineId,
        val rawCount: Int,
        val validCount: Int,
        val error: String?
    ) : ExtractionEvent
    data class Done(val streams: List<StreamInfo>) : ExtractionEvent
}

data class ExtractionSettings(
    val mode: RunMode = RunMode.SEQUENTIAL,
    val customOrder: List<EngineId> = EngineId.defaultOrder,
    val enabled: Set<EngineId> = EngineId.entries.toSet(),
    val timeoutSeconds: Int = 25
) {
    /** The engines that will run, in the order they will be tried. */
    fun activeEngines(): List<EngineId> {
        val order = if (mode == RunMode.CUSTOM) customOrder else EngineId.defaultOrder
        return order.filter { it in enabled }
    }
}

interface Extractor {
    val id: EngineId
    suspend fun extract(pageUrl: String, timeoutMs: Long): List<RawCandidate>
}
