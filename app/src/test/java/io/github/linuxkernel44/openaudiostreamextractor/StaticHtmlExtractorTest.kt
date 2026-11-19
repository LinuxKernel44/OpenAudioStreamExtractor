package io.github.linuxkernel44.openaudiostreamextractor

import io.github.linuxkernel44.openaudiostreamextractor.extract.PlaylistParser
import io.github.linuxkernel44.openaudiostreamextractor.extract.StaticHtmlExtractor
import io.github.linuxkernel44.openaudiostreamextractor.extract.StreamValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticHtmlExtractorTest {
    private val json = """{"hd":"https://streams.example-radios.fr/play/radios/fg/5gW/any/86400/17a.tok%2Fx%3D?format=hd","hls":"https://streams.example-radios.fr/play/radios/fg/5gW/any/86400/17a.tok%2Fx%3D?format=hls","id":"5gW","imageUrl":"https://cdn.example.com/a.jpeg","label":"RADIO FG","type":"RADIO"}"""

    @Test fun findsKeyedStreamsWithoutExtension() {
        val urls = PlaylistParser.findKeyedStreamUrls(json)
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.contains("format=") })
    }

    @Test fun scanIgnoresImages() {
        val urls = StaticHtmlExtractor().scan(json, "https://www.radiofg.com/")
        assertFalse(urls.any { it.endsWith(".jpeg") })
        assertTrue(urls.any { it.contains("format=hd") })
    }

    @Test fun findsLabelNextToUrl() {
        val url = PlaylistParser.findKeyedStreamUrls(json).first()
        assertEquals("RADIO FG", StaticHtmlExtractor().nameNear(json, url))
    }

    @Test fun nameMatchesSiteHost() {
        assertTrue(StreamValidator.nameMatchesSite("RADIO FG", "https://www.radiofg.com/"))
        assertFalse(StreamValidator.nameMatchesSite("FG BOB SINCLAR", "https://www.radiofg.com/"))
    }
}
