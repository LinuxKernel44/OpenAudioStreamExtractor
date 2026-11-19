package io.github.linuxkernel44.openaudiostreamextractor.extract

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Owns the single GeckoRuntime, the bundled extensions (uBlock Origin + our helper) and
 * two sessions: a headless one used for extraction and a visible one for the Browser tab.
 */
class GeckoBridge(private val context: Context) {

    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .aboutConfigEnabled(false)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .build()
        GeckoRuntime.create(context.applicationContext, settings)
    }

    private val mutex = Mutex()
    private var initialized = false
    @Volatile private var port: WebExtension.Port? = null
    private val inbox = CopyOnWriteArrayList<JSONObject>()
    private var extractionSession: GeckoSession? = null

    /** Session shown by the Browser tab. Created lazily on the main thread. */
    val browseSession: GeckoSession by lazy {
        GeckoSession(GeckoSessionSettings.Builder().build()).also { it.open(runtime) }
    }

    private suspend fun <T> GeckoResult<T>.await(): T = suspendCoroutine { cont ->
        accept({ v -> cont.resume(v as T) }, { e -> cont.resumeWithException(e ?: RuntimeException("GeckoResult failed")) })
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            (message as? JSONObject)?.let { inbox.add(it) }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (this@GeckoBridge.port === port) this@GeckoBridge.port = null
        }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            port.setDelegate(portDelegate)
            this@GeckoBridge.port = port
        }
    }

    /** Installs the bundled extensions once. Must be awaited before any session is used. */
    suspend fun ensureReady() = withContext(Dispatchers.Main) {
        if (initialized) return@withContext
        val controller = runtime.webExtensionController
        // Content blockers are best-effort: a failure must never stop extraction.
        runCatching { controller.ensureBuiltIn(UBO_LOCATION, UBO_ID).await() }
        runCatching { controller.ensureBuiltIn(IDCAC_LOCATION, IDCAC_ID).await() }
        val helper = controller.ensureBuiltIn(HELPER_LOCATION, HELPER_ID).await()
        helper.setMessageDelegate(messageDelegate, NATIVE_APP)
        initialized = true
    }

    private fun send(config: JSONObject) {
        val msg = JSONObject().put("type", "config").put("config", config)
        runCatching { port?.postMessage(msg) }
    }

    /**
     * Loads [url] in the headless session and collects media candidates reported by the helper
     * extension until [timeoutMs] elapses, or shortly after the first hit.
     */
    suspend fun collect(url: String, active: Boolean, timeoutMs: Long, graceMs: Long = 3500): List<RawCandidate> =
        mutex.withLock {
            ensureReady()
            val engine = if (active) EngineId.GECKO_DOM else EngineId.GECKO_NETWORK
            val found = LinkedHashMap<String, RawCandidate>()
            inbox.clear()

            val session = withContext(Dispatchers.Main) {
                extractionSession ?: GeckoSession(GeckoSessionSettings.Builder().build()).also {
                    it.open(runtime)
                    extractionSession = it
                }.also { }
            }
            withContext(Dispatchers.Main) { session.setActive(true) }

            // Wait for the extension background page to connect.
            withTimeoutOrNull(8000) { while (port == null) delay(150) }
            if (port == null) error("The helper extension did not connect")

            val config = JSONObject().put("capture", true).put("active", active).put("mute", true).put("reset", true)
            send(config)
            withContext(Dispatchers.Main) { session.loadUri(url) }

            val start = SystemClock.elapsedRealtime()
            var firstHit = 0L
            try {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - start >= timeoutMs) break
                    if (firstHit != 0L && now - firstHit >= graceMs) break
                    send(config.put("reset", false))
                    while (inbox.isNotEmpty()) {
                        val m = inbox.removeAt(0)
                        if (m.optString("type") != "candidate") continue
                        val u = m.optString("url")
                        if (u.isBlank() || PlaylistParser.isAdHost(u) || PlaylistParser.looksLikeSegment(u)) continue
                        if (found.putIfAbsent(u, RawCandidate(u, engine, m.optStr("icyName"), m.optStr("contentType"))) == null && firstHit == 0L) {
                            firstHit = SystemClock.elapsedRealtime()
                        }
                    }
                    delay(300)
                }
            } finally {
                send(JSONObject().put("capture", false).put("active", false).put("mute", false))
                withContext(Dispatchers.Main) {
                    session.loadUri("about:blank")
                    session.setActive(false)
                }
            }
            found.values.toList()
        }

    companion object {
        const val NATIVE_APP = "oase"
        const val UBO_LOCATION = "resource://android/assets/extensions/ublock/"
        const val UBO_ID = "uBlock0@raymondhill.net"
        const val IDCAC_LOCATION = "resource://android/assets/extensions/idcac/"
        const val IDCAC_ID = "jid1-KKzOGWgsW3Ao4Q@jetpack"
        const val HELPER_LOCATION = "resource://android/assets/extensions/helper/"
        const val HELPER_ID = "helper@openaudiostreamextractor.linuxkernel44.github.io"
    }
}

/** Like optString, but JSON null and blank become Kotlin null instead of "null". */
private fun JSONObject.optStr(key: String): String? =
    if (isNull(key)) null else optString(key).trim().ifEmpty { null }

class GeckoNetworkExtractor(private val bridge: GeckoBridge) : Extractor {
    override val id = EngineId.GECKO_NETWORK
    override suspend fun extract(pageUrl: String, timeoutMs: Long) = bridge.collect(pageUrl, active = false, timeoutMs = timeoutMs)
}

class GeckoDomExtractor(private val bridge: GeckoBridge) : Extractor {
    override val id = EngineId.GECKO_DOM
    override suspend fun extract(pageUrl: String, timeoutMs: Long) = bridge.collect(pageUrl, active = true, timeoutMs = timeoutMs)
}
