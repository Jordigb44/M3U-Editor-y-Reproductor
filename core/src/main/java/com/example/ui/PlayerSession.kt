package com.example.ui

import com.example.data.Channel

/**
 * What the player is currently showing: the channel list to zap through, the index of the
 * channel being watched, and the optional EPG (XMLTV) url of the active playlist.
 */
data class PlayerSession(
    val channels: List<Channel>,
    val index: Int,
    val epgUrl: String? = null
)
