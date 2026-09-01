package com.example.data

import java.io.InputStream

/**
 * Parser for XMLTV (EPG) files. Times use the format "yyyyMMddHHmmss Z".
 * Pure JVM implementation (line scan + regex) so it also runs in plain unit tests.
 */
object XmltvParser {

    private val attrRe = Regex("(\\w+)=\"([^\"]*)\"")
    private val titleRe = Regex("<title(?:\\s[^>]*)?>(.*?)</title>")
    private val descRe = Regex("<desc(?:\\s[^>]*)?>(.*?)</desc>")

    fun parse(input: InputStream, wantedChannelIds: Set<String>? = null): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        var pendingChannel: String? = null
        var pendingStart: String? = null
        var pendingStop: String? = null
        var pendingTitle: String? = null
        var pendingDesc: String = ""

        val wantedLower = wantedChannelIds?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }?.toSet()

        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                when {
                    trimmed.startsWith("<programme") -> {
                        val attrs = attrRe.findAll(trimmed)
                            .associate { it.groupValues[1] to it.groupValues[2] }
                        pendingChannel = attrs["channel"]
                        pendingStart = attrs["start"]
                        pendingStop = attrs["stop"]
                        pendingTitle = null
                        pendingDesc = ""
                    }
                    pendingChannel != null && trimmed.startsWith("<title") -> {
                        pendingTitle = titleRe.find(trimmed)?.groupValues?.get(1)?.trim().orEmpty()
                    }
                    pendingChannel != null && trimmed.startsWith("<desc") -> {
                        pendingDesc = descRe.find(trimmed)?.groupValues?.get(1)?.trim().orEmpty()
                    }
                    trimmed.contains("</programme>") -> {
                        val title = pendingTitle
                        val cid = pendingChannel
                        val s = pendingStart
                        val e = pendingStop
                        if (!title.isNullOrBlank() && cid != null && s != null && e != null) {
                            val keep = wantedLower == null || cid.trim().lowercase() in wantedLower
                            if (keep) {
                                val startMs = parseTime(s)
                                val stopMs = parseTime(e)
                                if (startMs != null && stopMs != null) {
                                    programs.add(EpgProgram(cid, title, startMs, stopMs, pendingDesc))
                                }
                            }
                        }
                        pendingChannel = null
                        pendingStart = null
                        pendingStop = null
                        pendingTitle = null
                        pendingDesc = ""
                    }
                }
            }
        }
        return programs
    }

    private fun normalize(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return str.lowercase()
            .replace(Regex("""^\[[^]]*\]\s*"""), "")
            .replace(Regex("""^[a-zA-Z]{2,3}\s*:\s*"""), "")
            .replace(Regex("""^[a-zA-Z]{2,3}\s*\|\s*"""), "")
            .replace(Regex("""\b(fhd|uhd|4k|hd|sd|hevc|h265|1080p|720p)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^a-zA-Z0-9]"""), "")
            .trim()
    }

    /** Helper to find matching programs for a channel by tvg-id, tvg-name, or channel name. */
    fun findProgramsForChannel(
        epgByChannel: Map<String, List<EpgProgram>>,
        tvgId: String?,
        tvgName: String?,
        channelName: String?
    ): List<EpgProgram>? {
        val candidates = listOfNotNull(tvgId, tvgName, channelName).map { it.trim() }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null

        // 1. Direct exact match
        for (cand in candidates) {
            epgByChannel[cand]?.let { return it }
        }

        // 2. Case-insensitive match
        for (cand in candidates) {
            val lower = cand.lowercase()
            for ((key, list) in epgByChannel) {
                if (key.trim().lowercase() == lower) return list
            }
        }

        // 3. Normalized name match
        for (cand in candidates) {
            val normCand = normalize(cand)
            if (normCand.isNotBlank()) {
                for ((key, list) in epgByChannel) {
                    if (normalize(key) == normCand) return list
                }
            }
        }

        return null
    }

    /** Parses "20260829140000 +0200" (or without offset) into epoch millis. */
    fun parseTime(raw: String): Long? {
        val t = raw.trim()
        if (t.length < 14) return null
        return try {
            val y = t.substring(0, 4).toInt()
            val mo = t.substring(4, 6).toInt()
            val d = t.substring(6, 8).toInt()
            val h = t.substring(8, 10).toInt()
            val mi = t.substring(10, 12).toInt()
            val s = t.substring(12, 14).toInt()
            val epochDays = daysFromCivil(y, mo, d)
            epochDays * 86_400_000L + h * 3_600_000L + mi * 60_000L + s * 1_000L - parseOffsetMs(t)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOffsetMs(raw: String): Long {
        val plus = raw.indexOf('+')
        val minus = raw.indexOf('-', 14)
        val idx = if (plus >= 0) plus else minus
        if (idx < 0 || idx + 5 > raw.length) return 0L
        val sign = if (raw[idx] == '-') -1 else 1
        val hh = raw.substring(idx + 1, idx + 3).toIntOrNull() ?: 0
        val mm = raw.substring(idx + 3, idx + 5).toIntOrNull() ?: 0
        return sign * (hh * 3600 + mm * 60) * 1000L
    }

    /** Days from civil (gregorian) date to epoch, using Howard Hinnant's algorithm. */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        var yy = y
        if (m <= 2) yy -= 1
        val era = yy / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** For a single channel's programmes: (currently playing, next programme). */
    fun nowAndNext(programs: List<EpgProgram>, nowMs: Long): Pair<EpgProgram?, EpgProgram?> {
        val sorted = programs.sortedBy { it.startMs }
        val now = sorted.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs }
        val next = sorted.firstOrNull { it.startMs >= nowMs }
        return now to next
    }
}
