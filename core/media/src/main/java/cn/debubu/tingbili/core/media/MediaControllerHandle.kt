package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

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
    override val bufferedPosition: Long get() = controller()?.bufferedPosition ?: 0L
    override val duration: Long get() = controller()?.duration ?: 0L
    override val isPlaying: Boolean get() = controller()?.isPlaying ?: false
    override val mediaItemCount: Int get() = controller()?.mediaItemCount ?: 0
    override var repeatMode: Int
        get() = controller()?.repeatMode ?: Player.REPEAT_MODE_OFF
        set(value) { controller()?.repeatMode = value }
    override var shuffleModeEnabled: Boolean
        get() = controller()?.shuffleModeEnabled ?: false
        set(value) { controller()?.shuffleModeEnabled = value }
    override val currentMediaItemIndex: Int get() = controller()?.currentMediaItemIndex ?: 0

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        controller()?.setMediaItems(items, startIndex, startPositionMs)
    }

    override fun replaceMediaItem(index: Int, item: MediaItem) {
        controller()?.replaceMediaItem(index, item)
    }

    override fun seekToNextMediaItem() { controller()?.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() { controller()?.seekToPreviousMediaItem() }

    override fun addMediaItem(index: Int, item: MediaItem) {
        controller()?.addMediaItem(index, item)
    }

    override fun removeMediaItem(index: Int) {
        controller()?.removeMediaItem(index)
    }

    override fun prepare() { controller()?.prepare() }
    override fun play() { controller()?.play() }
    override fun pause() { controller()?.pause() }
    override fun seekTo(positionMs: Long) { controller()?.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        controller()?.seekTo(mediaItemIndex, positionMs)
    }
    override fun setPlaybackSpeed(speed: Float) { controller()?.setPlaybackSpeed(speed) }
    override fun addListener(listener: Player.Listener) = connection.addListener(listener)
    override fun release() { /* 唯一释放权在 Service，此处为空 */ }

    /**
     * 冷启动刚进 App 时 MediaController 还在异步连接：这期间 setMediaItems 等命令会被静默丢弃，
     * 结果是「UI 换了新曲目、声音还是上一集」。播放前先等等连接完成，5s 兜底避免永久挂起。
     */
    override suspend fun awaitReady(): Boolean =
        withTimeoutOrNull(CONTROLLER_READY_TIMEOUT_MS) {
            connection.awaitController()
        } != null

    private companion object {
        const val CONTROLLER_READY_TIMEOUT_MS = 5_000L
    }
}
