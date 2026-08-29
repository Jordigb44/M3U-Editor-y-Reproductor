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
                if (header == DEFAULT_HEADER) header = trimmed
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

                val regex = Regex("([a-zA-Z0-9\\-]+)=\"([^\"]*)\"")
                val matches = regex.findAll(attrsStr)
                for (match in matches) {
                    attributes[match.groupValues[1]] = match.groupValues[2]
                }
            } else if (beforeComma.length > 8) {
                duration = beforeComma.substring(8).trim()
            }
        }

        val groupTitle = attributes.remove("group-title") ?: "Uncategorized"
        val logoUrl = attributes.remove("tvg-logo") ?: ""

        return Channel(
            name = name,
            groupTitle = if (groupTitle.isBlank()) "Uncategorized" else groupTitle,
            logoUrl = logoUrl,
            url = url,
            duration = duration,
            attributes = attributes
        )
    }
}
