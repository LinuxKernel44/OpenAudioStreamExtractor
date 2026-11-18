package io.github.linuxkernel44.openaudiostreamextractor.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Import/export of the station library as M3U, PLS or JSON. Pure and unit-testable. */
object PlaylistIO {

    @Serializable
    data class Entry(
        val name: String,
        val streamUrl: String,
        val pageUrl: String = "",
        val codec: String? = null,
        val bitrateKbps: Int? = null,
        val favorite: Boolean = false
    )

    @Serializable
    private data class JsonDoc(val format: String = "OpenAudioStreamExtractor", val version: Int = 1, val stations: List<Entry>)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun toEntries(list: List<StationEntity>) =
        list.map { Entry(it.name, it.streamUrl, it.pageUrl, it.codec, it.bitrateKbps, it.favorite) }

    fun toM3u(list: List<Entry>): String = buildString {
        appendLine("#EXTM3U")
        list.forEach {
            appendLine("#EXTINF:-1,${it.name.replace('\n', ' ')}")
            appendLine(it.streamUrl)
        }
    }

    fun toPls(list: List<Entry>): String = buildString {
        appendLine("[playlist]")
        list.forEachIndexed { i, e ->
            val n = i + 1
            appendLine("File$n=${e.streamUrl}")
            appendLine("Title$n=${e.name.replace('\n', ' ')}")
            appendLine("Length$n=-1")
        }
        appendLine("NumberOfEntries=${list.size}")
        appendLine("Version=2")
    }

    fun toJson(list: List<Entry>): String = json.encodeToString(JsonDoc.serializer(), JsonDoc(stations = list))

    fun parseM3u(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var title: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> title = line.substringAfter(',', "").trim().ifEmpty { null }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    if (line.startsWith("http", true)) out += Entry(title ?: hostOf(line), line)
                    title = null
                }
            }
        }
        return out
    }

    fun parsePls(text: String): List<Entry> {
        val files = mutableMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val m = Regex("^(File|Title)(\\d+)=(.*)$", RegexOption.IGNORE_CASE).find(line) ?: continue
            val n = m.groupValues[2].toInt()
            if (m.groupValues[1].equals("File", true)) files[n] = m.groupValues[3].trim() else titles[n] = m.groupValues[3].trim()
        }
        return files.toSortedMap().filter { it.value.startsWith("http", true) }
            .map { (n, url) -> Entry(titles[n]?.ifEmpty { null } ?: hostOf(url), url) }
    }

    fun parseJson(text: String): List<Entry> =
        json.decodeFromString(JsonDoc.serializer(), text).stations

    /** Detects the format from the content itself. */
    fun parseAny(text: String): List<Entry> {
        val t = text.trimStart()
        return when {
            t.startsWith("{") -> parseJson(t)
            t.startsWith("[playlist]", true) -> parsePls(t)
            else -> parseM3u(t)
        }
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url
}
