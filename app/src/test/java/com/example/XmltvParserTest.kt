package com.example

import com.example.data.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class XmltvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <programme channel="La1.TV" start="20260829050000 +0000" stop="20260829075000 +0000">
            <title>Noticias 24H</title>
          </programme>
          <programme channel="La1.TV" start="20260829075000 +0000" stop="20260829085500 +0000">
            <title>Viaje al centro de la tele</title>
          </programme>
          <programme channel="Otro.TV" start="20260829050000 +0000" stop="20260829060000 +0000">
            <title>Otro canal</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun parse(filter: Set<String>? = null) =
        XmltvParser.parse(ByteArrayInputStream(xml.toByteArray()), filter)

    @Test
    fun `parses programmes and filters by wanted channels`() {
        val programs = parse(setOf("La1.TV"))
        assertEquals(2, programs.size)
        assertTrue(programs.all { it.channelId == "La1.TV" })
        assertEquals("Noticias 24H", programs[0].title)
        assertEquals("Viaje al centro de la tele", programs[1].title)
        assertTrue(programs[0].stopMs > programs[0].startMs)
    }

    @Test
    fun `parses all when no filter`() {
        assertEquals(3, parse().size)
    }

    @Test
    fun `time parsing handles utc offsets`() {
        val utc = XmltvParser.parseTime("20260829050000 +0000")!!
        val plus2 = XmltvParser.parseTime("20260829070000 +0200")!!
        assertEquals(utc, plus2)
        assertNull(XmltvParser.parseTime("garbage"))
    }

    @Test
    fun `now and next selection`() {
        val programs = parse(setOf("La1.TV"))
        // 06:00Z is inside programme 1 (05:00Z-07:00Z); next starts at 07:00Z.
        val t1 = XmltvParser.parseTime("20260829060000 +0000")!!
        val (current, next) = XmltvParser.nowAndNext(programs, t1)
        assertEquals("Noticias 24H", current?.title)
        assertEquals("Viaje al centro de la tele", next?.title)
    }
}
