package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.model.Track

/**
 * Single source of truth for UI. Mirrors ExoPlayer state + queue.
 * Produced by PlayerManager.state: StateFlow<PlaybackState>
 */
data class PlaybackState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val repeatMode: Int = REPEAT_MODE_OFF,
    val speed: Float = 1f,
) {
    companion object {
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
    }
}
