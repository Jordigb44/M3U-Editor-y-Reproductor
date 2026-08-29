package com.example.tv

/**
 * Bridges hardware keys handled at the Activity level (e.g. volume buttons for channel
 * zapping) into the player that is currently open. Registered by the player while visible.
 */
object PlayerKeyRouter {
    /** Delta: -1 previous channel, +1 next channel. Null while no player is open. */
    var onZap: ((Int) -> Unit)? = null
}
