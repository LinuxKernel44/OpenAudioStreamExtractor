package io.github.linuxkernel44.openaudiostreamextractor.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Probes candidate URLs: checks headers, resolves playlists (PLS/M3U/XSPF/HLS),
 * rejects HTML, ads and media segments, and classifies what is left.
 */
class StreamValidator(baseClient: OkHttpClient = defaultClient()) {

    private val client = baseClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun validateAll(candidates: List<RawCandidate>): List<StreamInfo> = coroutineScope {
        val gate = Semaphore(6)
        candidates.distinctBy { it.url }
            .map { c -> async(Dispatchers.IO) { gate.withPermit { runCatching { validate(c) }.getOrDefault(emptyList()) } } }
            .awaitAll()
            .flatten()
            .let(::rankAndDedupe)
            .let { ranked -> markRefererNeeds(ranked, candidates) }
    }

    /** For the best results, checks whether the stream still works without a Referer header. */
    private suspend fun markRefererNeeds(ranked: List<StreamInfo>, raw: List<RawCandidate>): List<StreamInfo> = coroutineScope {
        val referers = raw.associate { it.url to it.referer }
        ranked.mapIndexed { i, s ->
            async(Dispatchers.IO) {
                val ref = referers[s.url] ?: s.requiresReferer
                if (i >= 3 || ref == null || s.url.contains("://localhost")) return@async s
                val ok = runCatching { probe(RawCandidate(s.url, s.engine, referer = null), s.url, 0, null).isNotEmpty() }.getOrDefault(true)
                if (ok) s else s.copy(requiresReferer = ref)
            }
        }.awaitAll()
    }

    suspend fun validate(candidate: RawCandidate): List<StreamInfo> = withContext(Dispatchers.IO) {
        probe(candidate, candidate.url, depth = 0, referer = candidate.referer)
    }

    private fun request(url: String, referer: String? = null): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Icy-MetaData", "1")
        .header("Accept", "*/*")
        .apply {
            if (referer != null) {
                header("Referer", referer)
                originOf(referer)?.let { header("Origin", it) }
            }
        }
        .build()

    private fun probe(c: RawCandidate, url: String, depth: Int, referer: String? = c.referer): List<StreamInfo> {
        if (depth > 3 || PlaylistParser.isAdHost(url) || PlaylistParser.looksLikeSegment(url)) return emptyList()
        val response = try {
            client.newCall(request(url, referer)).execute()
        } catch (_: Exception) {
            return emptyList()
        }
        response.use { r ->
            if (!r.isSuccessful) return emptyList()
            val finalUrl = r.request.url.toString()
            val type = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val isIcy = r.headers.names().any { it.lowercase().startsWith("icy-") }

            return when {
                type.startsWith("text/html") || type == "application/xhtml+xml" || type == "application/json" -> emptyList()
                isPlaylistType(type, finalUrl) -> handlePlaylist(c, r, finalUrl, depth, referer)
                type.startsWith("audio/") || type == "application/ogg" || type == "application/octet-stream" ||
                    type == "video/ogg" || isIcy || type.isEmpty() -> handleAudio(c, r, url, type, isIcy)
                else -> emptyList()
            }
        }
    }

    private fun isPlaylistType(type: String, url: String): Boolean =
        type.contains("mpegurl") || type.contains("scpls") || type == "audio/x-ms-asf" || type.contains("xspf") ||
            type == "application/pls" || type == "application/pls+xml" ||
            (type.startsWith("text/") && Regex("""\.(m3u8?|pls|xspf)(\?|#|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url))

    private fun readText(r: Response, max: Long = 512 * 1024): String {
        val source = r.body.source()
        source.request(max)
        val size = minOf(source.buffer.size, max)
        return source.buffer.readUtf8(size)
    }

    private fun handlePlaylist(c: RawCandidate, r: Response, url: String, depth: Int, referer: String?): List<StreamInfo> {
        val text = readText(r)
        if (text.trimStart().startsWith("#EXTM3U") && PlaylistParser.isHls(text)) return handleHls(c, text, url, depth, referer)
        val entries = when {
            text.contains("[playlist]", ignoreCase = true) -> PlaylistParser.parsePls(text, url)
            text.contains("<location>", ignoreCase = true) -> PlaylistParser.parseXspf(text, url)
            else -> PlaylistParser.parseM3u(text, url)
        }.distinct().take(5)
        return entries.flatMap { probe(c, it, depth + 1, referer) }
    }

    private fun handleHls(c: RawCandidate, text: String, url: String, depth: Int, referer: String?): List<StreamInfo> {
        val info = PlaylistParser.parseHls(text, url)
        if (info.isMaster) {
            val audio = info.variants.filter { !it.hasVideo }
            if (audio.isEmpty()) return emptyList()
            val best = audio.maxByOrNull { it.bandwidth ?: 0 } ?: return emptyList()
            // Confirm the chosen rendition is a live audio media playlist.
            val ok = runCatching {
                client.newCall(request(best.url, referer)).execute().use { rr ->
                    rr.isSuccessful && PlaylistParser.isHls(readText(rr))
                }
            }.getOrDefault(false)
            if (!ok) return emptyList()
            return listOf(build(c, url, StreamKind.HLS, "application/vnd.apple.mpegurl", codecFromHls(best.codecs), best.bandwidth?.div(1000), null, null, live = true))
        }
        if (info.segments.isEmpty()) return emptyList()
        if (info.segments.any { Regex("""\.(m4v|webm)(\?|#|$)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }) return emptyList()
        val codec = when {
            info.segments.first().contains(Regex("""\.aac(\?|#|$)""", RegexOption.IGNORE_CASE)) -> "AAC"
            info.segments.first().contains(Regex("""\.mp3(\?|#|$)""", RegexOption.IGNORE_CASE)) -> "MP3"
            else -> null
        }
        return listOf(build(c, url, StreamKind.HLS, "application/vnd.apple.mpegurl", codec, null, null, null, live = info.isLive))
    }

    private fun handleAudio(c: RawCandidate, r: Response, url: String, type: String, isIcy: Boolean): List<StreamInfo> {
        val source = r.body.source()
        // Make sure real audio bytes arrive and the body is not a disguised page.
        val head = try {
            source.request(1024)
            source.buffer.readByteArray(minOf(source.buffer.size, 1024L))
        } catch (_: Exception) {
            return emptyList()
        }
        if (head.isEmpty()) return emptyList()
        val magic = sniff(head)
        if (type == "application/octet-stream" || type.isEmpty()) {
            if (!isIcy && magic == null) return emptyList()
        }
        val textStart = head.take(64).toByteArray().toString(Charsets.ISO_8859_1).trimStart().lowercase()
        if (textStart.startsWith("<!doctype") || textStart.startsWith("<html")) return emptyList()

        val contentLength = r.header("Content-Length")?.toLongOrNull()
        val live = isIcy || contentLength == null || contentLength <= 0L
        val codec = codecFromType(type) ?: magic
        val br = r.header("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull()
        val kind = when {
            isIcy -> StreamKind.ICECAST
            live -> StreamKind.HTTP_AUDIO
            else -> StreamKind.ON_DEMAND
        }
        return listOf(
            build(c, url, kind, type.ifEmpty { null }, codec, br, r.header("icy-name")?.trim()?.takeIf { it.length > 2 }, r.header("icy-genre")?.trim()?.ifEmpty { null }, live)
        )
    }

    private fun sniff(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 'I'.code && at(1) == 'D'.code && at(2) == '3'.code -> "MP3"
            at(0) == 0xFF && (at(1) and 0xF6) == 0xF0 -> "AAC"
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "MP3"
            at(0) == 'O'.code && at(1) == 'g'.code && at(2) == 'g'.code && at(3) == 'S'.code -> "Ogg"
            at(0) == 'f'.code && at(1) == 'L'.code && at(2) == 'a'.code && at(3) == 'C'.code -> "FLAC"
            else -> null
        }
    }

    private fun codecFromType(type: String): String? = when {
        type == "audio/mpeg" || type == "audio/mp3" -> "MP3"
        type.contains("aac") -> "AAC"
        type == "audio/mp4" || type == "audio/x-m4a" -> "AAC"
        type.contains("ogg") -> "Ogg"
        type.contains("opus") -> "Opus"
        type.contains("flac") -> "FLAC"
        else -> null
    }

    private fun codecFromHls(codecs: String?): String? = when {
        codecs == null -> null
        codecs.contains("mp4a") -> "AAC"
        codecs.contains("mp3") -> "MP3"
        codecs.contains("opus") -> "Opus"
        else -> null
    }

    private fun build(
        c: RawCandidate, url: String, kind: StreamKind, type: String?, codec: String?, bitrate: Int?,
        name: String?, genre: String?, live: Boolean
    ): StreamInfo {
        var score = when (kind) {
            StreamKind.ICECAST -> 100
            StreamKind.HTTP_AUDIO -> 85
            StreamKind.HLS -> if (live) 70 else 25
            StreamKind.ON_DEMAND -> 20
        }
        score += when (c.engine) {
            EngineId.KNOWN_API -> 10
            EngineId.GECKO_NETWORK -> 6
            EngineId.GECKO_DOM -> 4
            EngineId.STATIC_PARSER -> 2
        }
        if (url.startsWith("https://")) score += 3
        if (name != null || c.name != null) score += 3
        score += nameSiteScore(c.name ?: name, c.referer)
        if (name?.trim()?.length ?: 0 < 2 && c.name == null) score -= 10
        if (bitrate != null) score += minOf(bitrate, 192) / 24
        if (Regex("""(preroll|pre-roll|advert|/ads?/|jingle|promo|sponsor)""", RegexOption.IGNORE_CASE).containsMatchIn(url)) score -= 40
        return StreamInfo(url, c.engine, kind, type, codec, bitrate, name ?: c.name, genre, live, score)
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

        fun originOf(url: String): String? = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" + if (it.port != HttpUrl.defaultPort(it.scheme)) ":${it.port}" else "" }

        /** True when a station name looks like the site it was found on (e.g. "RADIO FG" on radiofg.com). */
        fun nameMatchesSite(name: String?, pageUrl: String?): Boolean = nameSiteScore(name, pageUrl) > 0

        /** 60 for an exact match ("RADIO FG" on radiofg.com), 25 for a close one, else 0. */
        fun nameSiteScore(name: String?, pageUrl: String?): Int {
            if (name.isNullOrBlank() || pageUrl == null) return 0
            val host = pageUrl.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: return 0
            val parts = host.split('.')
            val label = (if (parts.size >= 2) parts[parts.size - 2] else parts[0]).lowercase().filter { it.isLetterOrDigit() }
            val n = name.lowercase().filter { it.isLetterOrDigit() }
            return when {
                label.length < 3 -> 0
                n == label -> 60
                n.contains(label) && n.length <= label.length + 6 -> 25
                else -> 0
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()

        fun rankAndDedupe(list: List<StreamInfo>): List<StreamInfo> =
            list.groupBy { it.url.trimEnd('/') }
                .map { (_, g) -> g.maxBy { it.score } }
                .sortedByDescending { it.score }
    }
}
