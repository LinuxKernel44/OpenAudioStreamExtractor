package io.github.linuxkernel44.openaudiostreamextractor

import io.github.linuxkernel44.openaudiostreamextractor.extract.PlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParserTest {
    @Test fun parsesPls() {
        val pls = "[playlist]\nFile1=http://a.example/live.mp3\nTitle1=A\nFile2=/rel\nNumberOfEntries=2"
        assertEquals(listOf("http://a.example/live.mp3", "http://a.example/rel"), PlaylistParser.parsePls(pls, "http://a.example/x.pls"))
    }

    @Test fun parsesM3u() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Name\nhttps://s.example/stream\n"
        assertEquals(listOf("https://s.example/stream"), PlaylistParser.parseM3u(m3u, "https://s.example/l.m3u"))
    }

    @Test fun detectsHlsMasterAndSkipsVideo() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.5"
            audio64.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            video.m3u8
        """.trimIndent()
        assertTrue(PlaylistParser.isHls(master))
        val info = PlaylistParser.parseHls(master, "https://h.example/master.m3u8")
        assertTrue(info.isMaster)
        assertEquals(2, info.variants.size)
        assertFalse(info.variants[0].hasVideo)
        assertTrue(info.variants[1].hasVideo)
    }

    @Test fun hlsMediaPlaylistLiveness() {
        val live = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg1.aac\n#EXTINF:10,\nseg2.aac"
        assertTrue(PlaylistParser.parseHls(live, "https://h.example/p.m3u8").isLive)
        assertFalse(PlaylistParser.parseHls("$live\n#EXT-X-ENDLIST", "https://h.example/p.m3u8").isLive)
    }

    @Test fun findsStreamUrlsInEscapedScript() {
        val js = """var s = {"url":"https:\/\/stream.example.com\/radio.mp3?x=1&y=2","img":"https://x.example/a.png","ad":"https://doubleclick.net/a.mp3"};"""
        val urls = PlaylistParser.findStreamUrls(js)
        assertEquals(listOf("https://stream.example.com/radio.mp3?x=1&y=2"), urls)
    }

    @Test fun segmentsAreRejected() {
        assertTrue(PlaylistParser.looksLikeSegment("https://x.example/seg1.ts"))
        assertFalse(PlaylistParser.looksLikeSegment("https://x.example/live.mp3"))
    }
}
