package com.example.data

import java.util.UUID

data class Channel(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var groupTitle: String,
    var logoUrl: String,
    var url: String,
    var duration: String = "-1",
    val attributes: MutableMap<String, String> = mutableMapOf()
) {
    /** Removes characters that would corrupt the #EXTINF line: line breaks and double quotes
     *  (quoted attribute values must not contain a literal "). */
    private fun sanitize(value: String): String = value
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("\"", "'")
        .trim()

    fun toM3uString(): String {
        val attrs = attributes.toMutableMap()
        if (groupTitle.isNotBlank()) attrs["group-title"] = groupTitle
        if (logoUrl.isNotBlank()) attrs["tvg-logo"] = logoUrl

        val attrsStr = attrs.entries.joinToString(" ") { "${it.key}=\"${sanitize(it.value)}\"" }
        val prefix = if (attrsStr.isNotEmpty()) " $attrsStr" else ""
        val safeDuration = duration.replace("\r", "").replace("\n", "").trim().ifBlank { "-1" }
        val safeUrl = url.replace("\r", "").replace("\n", "").trim()
        return "#EXTINF:${safeDuration}${prefix},${sanitize(name)}\n${safeUrl}"
    }
}
