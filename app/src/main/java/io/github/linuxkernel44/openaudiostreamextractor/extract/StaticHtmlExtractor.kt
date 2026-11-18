package io.github.linuxkernel44.openaudiostreamextractor.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fetches the page over plain HTTP and scans HTML, same-site scripts and iframes for stream URLs. */
class StaticHtmlExtractor(baseClient: OkHttpClient = StreamValidator.defaultClient()) : Extractor {
    override val id = EngineId.STATIC_PARSER

    private val client = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private fun fetch(url: String, maxBytes: Long = 2L * 1024 * 1024): String? = try {
        client.newCall(
            Request.Builder().url(url).header("User-Agent", StreamValidator.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*").build()
        ).execute().use { r ->
            if (!r.isSuccessful) null else {
                val s = r.body.source()
                s.request(maxBytes)
                s.buffer.readUtf8(minOf(s.buffer.size, maxBytes))
            }
        }
    } catch (_: Exception) {
        null
    }

    override suspend fun extract(pageUrl: String, timeoutMs: Long): List<RawCandidate> = withContext(Dispatchers.IO) {
        val html = fetch(pageUrl) ?: return@withContext emptyList()
        val urls = LinkedHashSet<String>()
        urls += scan(html, pageUrl)

        val iframes = attrValues(html, "iframe", "src").mapNotNull { PlaylistParser.resolve(pageUrl, it) }.distinct().take(3)
        for (f in iframes) fetch(f, 1024 * 1024)?.let { urls += scan(it, f) }

        val scripts = attrValues(html, "script", "src").mapNotNull { PlaylistParser.resolve(pageUrl, it) }
            .filter { !PlaylistParser.isAdHost(it) && Regex("(player|radio|stream|main|app|bundle|index|live)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .distinct().take(4)
        for (s in scripts) fetch(s, 1024 * 1024)?.let { urls += PlaylistParser.findStreamUrls(it) }

        urls.take(40).map { RawCandidate(it, id) }
    }

    internal fun scan(html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        // Explicit media elements and metadata first: highest confidence.
        for (tag in listOf("audio", "source")) attrValues(html, tag, "src").forEach { PlaylistParser.resolve(base, it)?.let(out::add) }
        Regex("""<meta[^>]+(?:property|name)=["']og:audio(?::secure_url)?["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { PlaylistParser.resolve(base, PlaylistParser.unescape(it.groupValues[1]))?.let(out::add) }
        Regex(""""(?:contentUrl|embedUrl|audio)"\s*:\s*"([^"]+)"""").findAll(html)
            .forEach { PlaylistParser.resolve(base, PlaylistParser.unescape(it.groupValues[1]))?.let(out::add) }
        Regex("""<link[^>]+type=["']audio/[^"']+["'][^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { PlaylistParser.resolve(base, it.groupValues[1])?.let(out::add) }
        out += PlaylistParser.findStreamUrls(html)
        return out.filter { !PlaylistParser.isAdHost(it) }
    }

    private fun attrValues(html: String, tag: String, attr: String): List<String> =
        Regex("""<$tag\b[^>]*?\b$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { PlaylistParser.unescape(it.groupValues[1]) }.toList()
}
