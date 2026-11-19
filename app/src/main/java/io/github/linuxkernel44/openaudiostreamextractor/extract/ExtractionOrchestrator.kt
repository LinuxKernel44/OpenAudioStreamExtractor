package io.github.linuxkernel44.openaudiostreamextractor.extract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Runs the enabled engines according to the user's chosen mode and validates their output. */
class ExtractionOrchestrator(
    private val engines: Map<EngineId, Extractor>,
    private val validator: StreamValidator
) {

    fun run(rawInput: String, settings: ExtractionSettings): Flow<ExtractionEvent> = channelFlow {
        val pageUrl = normalizeUrl(rawInput)
        val timeoutMs = settings.timeoutSeconds * 1000L
        val order = settings.activeEngines().mapNotNull { engines[it] }

        suspend fun runOne(engine: Extractor): List<StreamInfo> {
            send(ExtractionEvent.EngineStarted(engine.id))
            return try {
                val raw = withTimeoutOrNull(timeoutMs + 15_000) { engine.extract(pageUrl, timeoutMs) }.orEmpty()
                    .map { if (it.referer == null) it.copy(referer = pageUrl) else it }
                val valid = validator.validateAll(raw)
                send(ExtractionEvent.EngineFinished(engine.id, raw.size, valid.size, null))
                valid
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                send(ExtractionEvent.EngineFinished(engine.id, 0, 0, e.message ?: e.javaClass.simpleName))
                emptyList()
            }
        }

        if (settings.mode == RunMode.PARALLEL) {
            val results = Channel<List<StreamInfo>>(Channel.UNLIMITED)
            val jobs: List<Job> = order.map { e -> launch { results.send(runOne(e)) } }
            val all = mutableListOf<StreamInfo>()
            var remaining = order.size
            while (remaining > 0) {
                val r = results.receive()
                remaining--
                all += r
                if (r.isNotEmpty()) {
                    // Short grace period so engines finishing right behind can contribute candidates.
                    withTimeoutOrNull(1500) {
                        while (remaining > 0) {
                            all += results.receive()
                            remaining--
                        }
                    }
                    break
                }
            }
            jobs.forEach { it.cancel() }
            send(ExtractionEvent.Done(StreamValidator.rankAndDedupe(all)))
        } else {
            var found = emptyList<StreamInfo>()
            for (e in order) {
                found = runOne(e)
                if (found.isNotEmpty()) break
            }
            send(ExtractionEvent.Done(found))
        }
    }

    companion object {
        fun normalizeUrl(input: String): String {
            val t = input.trim()
            return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
        }
    }
}
