package com.example.ui

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId

/**
 * Dynamic/adaptive buffering to avoid micro-freezes on unstable IPTV streams.
 *
 * Starts playback quickly with a small buffer, but after every rebuffer episode it
 * demands more buffered data before resuming (up to [maxResumeMs]), riding out the
 * network jitter that causes micro-cuts. It keeps loading up to [maxBufferMs], and
 * once the buffer fills the recovery growth resets, so it relaxes when the network
 * is healthy again.
 */
@OptIn(UnstableApi::class)
class AdaptiveLoadControl(
    /** Keep loading until this much is buffered; reaching it also resets recovery. */
    private val maxBufferMs: Long = 30_000L,
    /** Buffer required before the very first playback start. */
    private val initialStartMs: Long = 2_500L,
    /** Base buffer required to resume after a rebuffer. */
    private val resumeAfterRebufferMs: Long = 6_000L,
    /** Extra buffer demanded for each additional rebuffer episode. */
    private val growthPerRebufferMs: Long = 4_000L,
    /** Hard cap for the required resume buffer. */
    private val maxResumeMs: Long = 15_000L
) : DefaultLoadControl() {

    private var inRebufferEpisode = false
    private var rebufferCount = 0

    override fun onStopped(playerId: PlayerId) {
        super.onStopped(playerId)
        inRebufferEpisode = false
        rebufferCount = 0
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // Healthy buffer: the network caught up, relax the recovery growth.
        if (parameters.bufferedDurationUs >= maxBufferMs * 1000) {
            rebufferCount = 0
            inRebufferEpisode = false
            return false
        }
        return super.shouldContinueLoading(parameters) || parameters.bufferedDurationUs < maxBufferMs * 1000
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        // Count distinct rebuffer episodes (polled repeatedly during one episode,
        // so guard with a flag).
        if (parameters.rebuffering && !inRebufferEpisode) {
            inRebufferEpisode = true
            rebufferCount++
        } else if (!parameters.rebuffering) {
            inRebufferEpisode = false
        }

        val episodesBeforeThisOne = (rebufferCount - 1).coerceAtLeast(0)
        val baseMs = if (parameters.rebuffering) resumeAfterRebufferMs else initialStartMs
        val requiredMs = (baseMs + episodesBeforeThisOne * growthPerRebufferMs)
            .coerceAtMost(maxResumeMs)
        val requiredUs = (requiredMs * parameters.playbackSpeed).toLong() * 1000
        return parameters.bufferedDurationUs >= requiredUs
    }
}
