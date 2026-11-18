package io.github.linuxkernel44.openaudiostreamextractor

import io.github.linuxkernel44.openaudiostreamextractor.extract.EngineId
import io.github.linuxkernel44.openaudiostreamextractor.extract.RawCandidate
import io.github.linuxkernel44.openaudiostreamextractor.extract.StreamKind
import io.github.linuxkernel44.openaudiostreamextractor.extract.StreamValidator
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamValidatorTest {
    private lateinit var server: MockWebServer
    private val validator = StreamValidator()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.close() }

    private fun cand(path: String) = RawCandidate(server.url(path).toString(), EngineId.STATIC_PARSER)
    private fun mp3Body() = Buffer().write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0) + ByteArray(2000))

    @Test fun acceptsIcecastStream() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").addHeader("icy-name", "Test FM").addHeader("icy-br", "128").body(mp3Body()).build())
        val r = validator.validate(cand("/live"))
        assertEquals(1, r.size)
        assertEquals(StreamKind.ICECAST, r[0].kind)
        assertEquals("MP3", r[0].codec)
        assertEquals(128, r[0].bitrateKbps)
        assertEquals("Test FM", r[0].stationName)
    }

    @Test fun rejectsHtml() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html></html>").build())
        assertTrue(validator.validate(cand("/page")).isEmpty())
    }

    @Test fun resolvesPlsPlaylist() = runBlocking {
        val streamUrl = server.url("/real").toString()
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/x-scpls").body("[playlist]\nFile1=$streamUrl\n").build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/aacp").addHeader("icy-name", "X").body(mp3Body()).build())
        val r = validator.validate(cand("/a.pls"))
        assertEquals(1, r.size)
        assertEquals(streamUrl, r[0].url)
        assertEquals("AAC", r[0].codec)
    }

    @Test fun rejectsVideoOnlyHls() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/vnd.apple.mpegurl").body(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nv.m3u8\n").build())
        assertTrue(validator.validate(cand("/m.m3u8")).isEmpty())
    }

    @Test fun finiteFileIsOnDemandWithLowScore() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").addHeader("Content-Length", "2010").body(mp3Body()).build())
        val r = validator.validate(cand("/song.mp3"))
        assertEquals(StreamKind.ON_DEMAND, r[0].kind)
        assertTrue(r[0].score < 50)
    }
}
