package com.example

import com.example.data.Channel
import com.example.data.M3uParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class M3uParserTest {

    private suspend fun parse(content: String) =
        M3uParser.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))

    @Test
    fun `parses header, duration, name and attributes`() = runBlocking {
        val m3u = """
            #EXTM3U url-tvg="https://epg.example.com/guide.xml"
            #EXTINF:-1 tvg-id="cnn.us" tvg-logo="http://logo/cnn.png" group-title="Noticias",CNN
            http://stream.example.com/cnn.m3u8
        """.trimIndent()
        val result = parse(m3u)
        assertEquals(1, result.channels.size)
        assertEquals("#EXTM3U url-tvg=\"https://epg.example.com/guide.xml\"", result.header)
        val ch = result.channels.first()
        assertEquals("CNN", ch.name)
        assertEquals("Noticias", ch.groupTitle)
        assertEquals("http://logo/cnn.png", ch.logoUrl)
        assertEquals("-1", ch.duration)
        assertEquals("cnn.us", ch.attributes["tvg-id"])
        assertEquals("http://stream.example.com/cnn.m3u8", ch.url)
    }

    @Test
    fun `handles utf8 BOM at the start of the file`() = runBlocking {
        val content = "\uFEFF#EXTM3U\n#EXTINF:0 group-title=\"Deportes\",Futbol\nhttp://x/f.m3u8\n"
        val result = parse(content)
        assertEquals(1, result.channels.size)
        assertEquals("Futbol", result.channels.first().name)
        assertEquals("Deportes", result.channels.first().groupTitle)
        assertTrue(result.channels.none { it.url.startsWith("\uFEFF") })
    }

    @Test
    fun `raw url without EXTINF becomes unknown channel`() = runBlocking {
        val result = parse("#EXTM3U\nhttp://raw.example.com/stream.m3u8\n")
        assertEquals(1, result.channels.size)
        assertEquals("Unknown Channel", result.channels.first().name)
        assertEquals("Uncategorized", result.channels.first().groupTitle)
        assertEquals("http://raw.example.com/stream.m3u8", result.channels.first().url)
    }

    @Test
    fun `blank group title falls back to Uncategorized`() = runBlocking {
        val result = parse("#EXTM3U\n#EXTINF:-1 group-title=\"\" ,Canal\nhttp://x/c.m3u8\n")
        assertEquals("Uncategorized", result.channels.first().groupTitle)
    }

    @Test
    fun `empty or header-only playlist yields no channels`() = runBlocking {
        assertEquals(0, parse("#EXTM3U\n").channels.size)
        assertEquals(0, parse("").channels.size)
    }

    @Test
    fun `duration without attributes is parsed`() = runBlocking {
        val result = parse("#EXTM3U\n#EXTINF:45,Canal Sin Atributos\nhttp://x/a.m3u8\n")
        assertEquals("45", result.channels.first().duration)
    }

    @Test
    fun `comments and empty lines are ignored`() = runBlocking {
        val result = parse("#EXTM3U\n\n# comment\n#EXTINF:-1,Canal\nhttp://x/b.m3u8\n")
        assertEquals(1, result.channels.size)
        assertEquals("Canal", result.channels.first().name)
    }

    @Test
    fun `all extra attributes are preserved`() = runBlocking {
        val result = parse(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"1\" tvg-name=\"N\" audio-track=\"esp\" group-title=\"G\",N\nhttp://x/n.m3u8\n"
        )
        val ch = result.channels.first()
        assertEquals("esp", ch.attributes["audio-track"])
        assertEquals("1", ch.attributes["tvg-id"])
        val line = ch.toM3uString()
        assertTrue(line.contains("audio-track=\"esp\""))
    }

    @Test
    fun `toM3uString sanitizes quotes and newlines`() {
        val ch = Channel(
            name = "Canal \"VIP\"",
            groupTitle = "Series\nPremium",
            logoUrl = "http://logo/x.png",
            url = "http://stream.example.com/v.m3u8",
            duration = "-1",
            attributes = mutableMapOf("tvg-id" to "vip\"id")
        )
        val line = ch.toM3uString()
        val extInf = line.substringBefore('\n')
        // Attribute values must not contain a literal double quote.
        assertTrue(!extInf.contains("\"\""))
        assertTrue(extInf.contains("group-title=\"Series Premium\""))
        assertTrue(extInf.contains("tvg-id=\"vip'id\""))
        assertTrue(extInf.endsWith(",Canal 'VIP'"))
        assertEquals("http://stream.example.com/v.m3u8", line.substringAfter('\n'))
    }
}
