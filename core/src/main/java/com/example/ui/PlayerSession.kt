package com.example.ui

import com.example.data.Channel

/**
 * What the player is currently showing: the channel list to zap through and the
 * index of the channel being watched.
 */
data class PlayerSession(
    val channels: List<Channel>,
    val index: Int
)
