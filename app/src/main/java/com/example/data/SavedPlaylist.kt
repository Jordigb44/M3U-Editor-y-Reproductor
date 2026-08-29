package com.example.data

import java.util.UUID

data class SavedPlaylist(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val sourceUrlOrPath: String? = null,
    var channelCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
