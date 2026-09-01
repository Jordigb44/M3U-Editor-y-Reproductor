package com.example.data

import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of parsing a playlist: the raw #EXTM3U header line (with its attributes, e.g.
 *  url-tvg / x-tvg-url) plus the list of channels. */
data class ParsedM3u(
    val header: String,
    val channels: List<Channel>
)

object M3uParser {
    private const val DEFAULT_HEADER = "#EXTM3U"

    suspend fun parse(inputStream: InputStream): ParsedM3u = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(InputStreamReader(inputStream))
        var header = DEFAULT_HEADER
        var line: String?

        var currentExtInf = ""

        while (reader.readLine().also { line = it } != null) {
            // Strip a UTF-8 BOM if present (files saved by Windows editors often start with one).
            val trimmed = line!!.trim().removePrefix("\uFEFF")
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTM3U")) {
                // Prefer the header line that carries playlist attributes (e.g. url-tvg for EPG).
                val hasTvg = trimmed.contains("url-tvg") || trimmed.contains("x-tvg-url") || trimmed.contains("tvg-url")
                if (header == DEFAULT_HEADER || hasTvg) header = trimmed
                continue
            }

            if (trimmed.startsWith("#EXTINF:")) {
                currentExtInf = trimmed
            } else if (!trimmed.startsWith("#")) {
                if (currentExtInf.isNotEmpty()) {
                    val channel = parseExtInf(currentExtInf, trimmed)
                    channels.add(channel)
                    currentExtInf = ""
                } else {
                    // Raw URL without EXTINF, create a generic channel
                    channels.add(Channel(name = "Unknown Channel", groupTitle = "Uncategorized", logoUrl = "", url = trimmed))
                }
            }
        }
        ParsedM3u(header = header, channels = channels)
    }

    private val ATTR_REGEX = Regex("""([a-zA-Z0-9_\-]+)=(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")

    private fun parseExtInf(extInf: String, url: String): Channel {
        var duration = "-1"
        var name = "Unknown Channel"
        val attributes = mutableMapOf<String, String>()

        var inQuotes = false
        var commaIndex = -1
        for (i in extInf.indices) {
            val c = extInf[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                commaIndex = i
                break
            }
        }

        if (commaIndex != -1) {
            name = extInf.substring(commaIndex + 1).trim()
            val beforeComma = extInf.substring(0, commaIndex)

            val spaceIndex = beforeComma.indexOf(' ')
            if (spaceIndex != -1 && spaceIndex > 7) {
                duration = beforeComma.substring(8, spaceIndex).trim()
                val attrsStr = beforeComma.substring(spaceIndex).trim()

                val matches = ATTR_REGEX.findAll(attrsStr)
                for (match in matches) {
                    val key = match.groupValues[1]
                    val value = (match.groupValues[2].ifBlank { null }
                        ?: match.groupValues[3].ifBlank { null }
                        ?: match.groupValues[4]).trim()
                    attributes[key] = value
                }
            } else if (beforeComma.length > 8) {
                duration = beforeComma.substring(8).trim()
            }
        }

        val groupTitle = attributes.remove("group-title") ?: attributes.remove("group_title") ?: "Uncategorized"
        val logoUrl = attributes.remove("tvg-logo")
            ?: attributes.remove("tvg_logo")
            ?: attributes.remove("logo")
            ?: attributes.remove("url-logo")
            ?: attributes.remove("logo-url")
            ?: ""

        return Channel(
            name = name,
            groupTitle = if (groupTitle.isBlank()) "Uncategorized" else groupTitle,
            logoUrl = logoUrl.trim(),
            url = url,
            duration = duration,
            attributes = attributes
        )
    }
}
