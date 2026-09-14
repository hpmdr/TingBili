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
    /** 当前播放来源名称，例如收藏夹名、视频标题或“播放历史”。 */
    val sourceTitle: String? = null,
    val positionMs: Long = 0L,
    /** 当前音源已缓存/已缓冲到的位置，用于播放页进度条底层展示。 */
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val repeatMode: Int = REPEAT_MODE_OFF,
    val speed: Float = 1f,
) {
    companion object {
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
    }
}
