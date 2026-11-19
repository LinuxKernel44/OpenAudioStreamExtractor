package io.github.linuxkernel44.openaudiostreamextractor.extract

import java.net.URI

/** Pure helpers for parsing playlists and finding stream-like URLs in text. */
object PlaylistParser {

    fun resolve(base: String, ref: String): String? = try {
        URI(base).resolve(ref.trim()).toString().takeIf { it.startsWith("http", ignoreCase = true) }
    } catch (_: Exception) {
        null
    }

    fun parsePls(text: String, base: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("File", ignoreCase = true) && it.contains('=') }
            .mapNotNull { resolve(base, it.substringAfter('=')) }
            .toList()

    fun parseM3u(text: String, base: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { resolve(base, it) }
            .toList()

    fun parseXspf(text: String, base: String): List<String> =
        Regex("<location>\\s*([^<]+?)\\s*</location>", RegexOption.IGNORE_CASE)
            .findAll(text).mapNotNull { resolve(base, it.groupValues[1]) }.toList()

    fun isHls(text: String): Boolean =
        text.trimStart().startsWith("#EXTM3U") &&
            (text.contains("#EXT-X-STREAM-INF") || text.contains("#EXTINF") && text.contains("#EXT-X-"))

    data class HlsVariant(val url: String, val bandwidth: Int?, val codecs: String?, val hasVideo: Boolean)

    data class HlsInfo(
        val isMaster: Boolean,
        val variants: List<HlsVariant>,
        val isLive: Boolean,
        val segments: List<String>
    )

    fun parseHls(text: String, base: String): HlsInfo {
        val lines = text.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variants = mutableListOf<HlsVariant>()
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val attrs = l.substringAfter(':')
                    val bw = Regex("(?:^|,)BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    val codecs = Regex("CODECS=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
                    val hasRes = attrs.contains("RESOLUTION=")
                    val videoCodec = codecs?.contains(Regex("avc1|hvc1|hev1|vp0?9|av01")) == true
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        resolve(base, lines[j])?.let { variants += HlsVariant(it, bw, codecs, hasRes || videoCodec) }
                    }
                    i = j
                }
                i++
            }
            return HlsInfo(true, variants, true, emptyList())
        }
        val segs = lines.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { resolve(base, it) }
        return HlsInfo(false, emptyList(), !lines.any { it.startsWith("#EXT-X-ENDLIST") }, segs)
    }

    private val urlRegex = Regex("""https?://[^\s"'<>\\)\]},]+""", RegexOption.IGNORE_CASE)

    private val audioExt = Regex("""\.(mp3|aac|aacp|m3u8|m3u|pls|ogg|oga|opus|flac|m4a|xspf)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val streamHint = Regex(
        """(/;stream|/;\?type=|/stream\b|/listen\b|/live\b|icecast|shoutcast|streaming\.|stream\.|/mount|/radio/|\.radio\.|zeno\.fm|radio\.co|streamtheworld|\.akamaized\.net|streams?\.[a-z0-9-]+\.|/play/radios?/|[?&]format=(hd|sd|bd|hls|mp3|aac))""",
        RegexOption.IGNORE_CASE
    )
    private val junkExt = Regex(
        """\.(ts|m4s|mp4|webm|m4v|jpe?g|png|gif|svg|webp|ico|css|js|mjs|woff2?|ttf|eot|map|html?|php|json|xml|txt|pdf)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )
    private val adHosts = Regex(
        """(doubleclick|googlesyndication|googleadservices|adservice|imasdk|scorecardresearch|moatads|google-analytics|googletagmanager|facebook\.|twitter\.|gstatic|fonts\.g)""",
        RegexOption.IGNORE_CASE
    )

    /** JSON/JS properties that usually hold a stream URL, e.g. `"hd":"https://..."`. */
    private val streamKeyRegex = Regex(
        """["']?(?:hd|sd|bd|hls|mp3|aac|ogg|stream\w*|\w*stream|listen\w*|live\w*|audio\w*|source|src|file)["']?\s*[:=]\s*["'](https?://[^"'\s<>]+)["']""",
        RegexOption.IGNORE_CASE
    )

    /** URLs held by stream-like JSON keys. Their order in the text is preserved. */
    fun findKeyedStreamUrls(text: String): List<String> =
        streamKeyRegex.findAll(unescape(text)).map { it.groupValues[1] }
            .filter { !isAdHost(it) && !junkExt.containsMatchIn(it) || audioExt.containsMatchIn(it) }
            .distinct().toList()

    fun isAdHost(url: String): Boolean = adHosts.containsMatchIn(url)

    fun unescape(text: String): String =
        text.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").replace("\\u003d", "=")

    /**
     * Finds stream-like absolute URLs in [text]. Strongest signals (audio extensions) come first.
     */
    fun findStreamUrls(text: String, limit: Int = 40): List<String> {
        val strong = LinkedHashSet<String>()
        val weak = LinkedHashSet<String>()
        for (m in urlRegex.findAll(unescape(text))) {
            val u = m.value.trimEnd('.', ';', '\'', '"')
            if (isAdHost(u)) continue
            when {
                audioExt.containsMatchIn(u) -> strong += u
                junkExt.containsMatchIn(u) -> Unit
                streamHint.containsMatchIn(u) -> weak += u
            }
        }
        return (strong + weak).take(limit)
    }

    fun looksLikeSegment(url: String) = junkExt.containsMatchIn(url) && !audioExt.containsMatchIn(url)
}
