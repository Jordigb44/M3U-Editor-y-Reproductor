package com.example.data

/** One EPG (XMLTV) programme entry for a channel. */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long
)
