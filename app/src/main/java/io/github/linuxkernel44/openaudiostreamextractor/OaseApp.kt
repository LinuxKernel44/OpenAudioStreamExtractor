package io.github.linuxkernel44.openaudiostreamextractor

import android.app.Application
import io.github.linuxkernel44.openaudiostreamextractor.data.AppDatabase
import io.github.linuxkernel44.openaudiostreamextractor.data.SettingsStore
import io.github.linuxkernel44.openaudiostreamextractor.extract.EngineId
import io.github.linuxkernel44.openaudiostreamextractor.extract.ExtractionOrchestrator
import io.github.linuxkernel44.openaudiostreamextractor.extract.GeckoBridge
import io.github.linuxkernel44.openaudiostreamextractor.extract.GeckoDomExtractor
import io.github.linuxkernel44.openaudiostreamextractor.extract.GeckoNetworkExtractor
import io.github.linuxkernel44.openaudiostreamextractor.extract.KnownApiExtractor
import io.github.linuxkernel44.openaudiostreamextractor.extract.StaticHtmlExtractor
import io.github.linuxkernel44.openaudiostreamextractor.extract.StreamValidator

class OaseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.create(app)
    val settings = SettingsStore(app)
    val gecko = GeckoBridge(app)
    private val client = StreamValidator.defaultClient()
    val validator = StreamValidator(client)
    val orchestrator = ExtractionOrchestrator(
        mapOf(
            EngineId.KNOWN_API to KnownApiExtractor(client),
            EngineId.STATIC_PARSER to StaticHtmlExtractor(client),
            EngineId.GECKO_NETWORK to GeckoNetworkExtractor(gecko),
            EngineId.GECKO_DOM to GeckoDomExtractor(gecko)
        ),
        validator
    )
}
