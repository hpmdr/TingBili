package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PlayerHandle 的生产实现：把调用委托给当前 MediaController。
 * 未连接时读操作返回安全默认值、写操作为 no-op（调用方 play() 会 await 就绪）。
 * release() 故意为空实现——ExoPlayer 只由 TingBiliPlaybackService 释放。
 */
@Singleton
class MediaControllerHandle @Inject constructor(
    private val connection: PlaybackConnection,
) : PlayerHandle {
    private fun controller() = connection.controller.value

    override val currentPosition: Long get() = controller()?.currentPosition ?: 0L
    override val duration: Long get() = controller()?.duration ?: 0L
    override val isPlaying: Boolean get() = controller()?.isPlaying ?: false
    override var repeatMode: Int
        get() = controller()?.repeatMode ?: Player.REPEAT_MODE_OFF
        set(value) { controller()?.repeatMode = value }
    override val currentMediaItemIndex: Int get() = controller()?.currentMediaItemIndex ?: 0

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        controller()?.setMediaItems(items, startIndex, startPositionMs)
    }

    override fun prepare() { controller()?.prepare() }
    override fun play() { controller()?.play() }
    override fun pause() { controller()?.pause() }
    override fun seekTo(positionMs: Long) { controller()?.seekTo(positionMs) }
    override fun setPlaybackSpeed(speed: Float) { controller()?.setPlaybackSpeed(speed) }
    override fun addListener(listener: Player.Listener) = connection.addListener(listener)
    override fun release() { /* 唯一释放权在 Service，此处为空 */ }
}
