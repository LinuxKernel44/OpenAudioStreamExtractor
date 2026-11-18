package io.github.linuxkernel44.openaudiostreamextractor

import io.github.linuxkernel44.openaudiostreamextractor.data.PlaylistIO
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistIOTest {
    private val entries = listOf(
        PlaylistIO.Entry("Jazz FM", "https://a.example/jazz.mp3", "https://a.example", "MP3", 128, true),
        PlaylistIO.Entry("Rock", "http://b.example/rock", "", null, null, false)
    )

    @Test fun m3uRoundTrip() {
        val back = PlaylistIO.parseM3u(PlaylistIO.toM3u(entries))
        assertEquals(entries.map { it.name to it.streamUrl }, back.map { it.name to it.streamUrl })
    }

    @Test fun plsRoundTrip() {
        val back = PlaylistIO.parsePls(PlaylistIO.toPls(entries))
        assertEquals(entries.map { it.name to it.streamUrl }, back.map { it.name to it.streamUrl })
    }

    @Test fun jsonRoundTripAndAutoDetect() {
        val json = PlaylistIO.toJson(entries)
        assertEquals(entries, PlaylistIO.parseJson(json))
        assertEquals(entries, PlaylistIO.parseAny(json))
        assertEquals(2, PlaylistIO.parseAny(PlaylistIO.toPls(entries)).size)
        assertEquals(2, PlaylistIO.parseAny(PlaylistIO.toM3u(entries)).size)
    }
}
