package io.github.linuxkernel44.openaudiostreamextractor.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cheap, deterministic resolvers: the URL itself, Icecast/Shoutcast status endpoints,
 * Radio.co ids and the community radio-browser.info directory.
 */
class KnownApiExtractor(baseClient: OkHttpClient = StreamValidator.defaultClient()) : Extractor {
    override val id = EngineId.KNOWN_API

    private val client = baseClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private val directories = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", "OpenAudioStreamExtractor/0.1").build())
            .execute().use { if (it.isSuccessful) it.body.string() else null }
    } catch (_: Exception) {
        null
    }

    override suspend fun extract(pageUrl: String, timeoutMs: Long): List<RawCandidate> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, RawCandidate>()
        fun add(url: String, name: String? = null) {
            out.putIfAbsent(url, RawCandidate(url, id, name))
        }
        val http = pageUrl.toHttpUrlOrNull() ?: return@withContext emptyList()

        add(pageUrl) // The user may already have pasted a stream or playlist URL.

        // Radio.co: any URL carrying a station id like s1a2b3c4d5.
        Regex("""\bs[0-9a-f]{9}\b""").find(pageUrl)?.let { add("https://streaming.radio.co/${it.value}/listen") }

        // Icecast / Shoutcast origin probes.
        val origin = "${http.scheme}://${http.host}" + if (http.port != HttpUrlDefault.port(http.scheme)) ":${http.port}" else ""
        if (http.port != HttpUrlDefault.port(http.scheme) || Regex("(icecast|shoutcast|stream)", RegexOption.IGNORE_CASE).containsMatchIn(http.host)) {
            get("$origin/status-json.xsl")?.let { body ->
                runCatching {
                    val icestats = json.parseToJsonElement(body).jsonObject["icestats"]?.jsonObject
                    val src = icestats?.get("source")
                    val sources: List<JsonObject> = when (src) {
                        is JsonArray -> src.map { it.jsonObject }
                        is JsonObject -> listOf(src)
                        else -> emptyList()
                    }
                    sources.forEach { s ->
                        s["listenurl"]?.jsonPrimitive?.contentOrNull?.let { add(it, s["server_name"]?.jsonPrimitive?.contentOrNull) }
                    }
                }
            }
            add("$origin/;stream.mp3")
            add("$origin/stream")
            add("$origin/listen.pls")
        }

        // radio-browser.info: match by homepage host so unrelated stations are never returned.
        val host = http.host.removePrefix("www.")
        val label = host.split('.').let { if (it.size >= 2) it[it.size - 2] else it.first() }
        val slug = http.pathSegments.lastOrNull { it.isNotBlank() }?.replace('-', ' ')?.replace('_', ' ')
        val queries = listOfNotNull(slug?.takeIf { it.length > 2 }, label).distinct()
        run {
            for (q in queries) {
                for (dir in directories) {
                    val body = get(
                        "$dir/json/stations/search?name=${java.net.URLEncoder.encode(q, "UTF-8")}&hidebroken=true&limit=60&order=clickcount&reverse=true"
                    ) ?: continue
                    val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: continue
                    val hostMatches = arr.map { it.jsonObject }.filter { st ->
                        val homepage = st["homepage"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val hpHost = homepage.toHttpUrlOrNull()?.host?.removePrefix("www.")
                        hpHost != null && (hpHost == host || hpHost.endsWith(".$host") || host.endsWith(".$hpHost"))
                    }
                    // When the page path names a station (e.g. /groovesalad/), require the name to contain it.
                    val key = slug?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()
                    val chosen = if (key.length > 2) hostMatches.filter { st ->
                        st["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase().filter { it.isLetterOrDigit() }.contains(key)
                    } else hostMatches
                    chosen.forEach { st ->
                        val u = st["url_resolved"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                            ?: st["url"]?.jsonPrimitive?.contentOrNull
                        if (!u.isNullOrBlank()) add(u, st["name"]?.jsonPrimitive?.contentOrNull)
                    }
                    break // one working directory per query is enough
                }
            }
        }
        out.values.toList()
    }

    private object HttpUrlDefault {
        fun port(scheme: String) = if (scheme == "https") 443 else 80
    }
}
