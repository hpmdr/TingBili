package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Testable abstraction over ExoPlayer. Production delegates to [Player] (ExoPlayer),
 * tests use lightweight fake without implementing full Player interface.
 */
interface PlayerHandle {
    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean
    var repeatMode: Int
    val currentMediaItemIndex: Int
    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekTo(mediaItemIndex: Int, positionMs: Long) { seekTo(positionMs) }
    fun setPlaybackSpeed(speed: Float)
    fun addListener(listener: Player.Listener)
    fun release()
}

/**
 * Production implementation delegating to ExoPlayer (which implements Player).
 */
class ExoPlayerHandle(private val player: Player) : PlayerHandle {
    override val currentPosition: Long get() = player.currentPosition
    override val duration: Long get() = player.duration
    override val isPlaying: Boolean get() = player.isPlaying
    override var repeatMode: Int
        get() = player.repeatMode
        set(value) { player.repeatMode = value }
    override val currentMediaItemIndex: Int get() = player.currentMediaItemIndex

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        player.setMediaItems(items, startIndex, startPositionMs)
    }

    override fun prepare() = player.prepare()
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun addListener(listener: Player.Listener) = player.addListener(listener)
    override fun release() = player.release()
}
