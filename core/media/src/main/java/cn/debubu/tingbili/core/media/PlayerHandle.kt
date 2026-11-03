package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * 播放传输层抽象：生产走 MediaControllerHandle（经 MediaController 操作 Service 内 ExoPlayer），
 * 单测用轻量 Fake。注意 release() 语义——生产实现为空，唯一释放权在 TingBiliPlaybackService。
 */
interface PlayerHandle {
    val currentPosition: Long
    val bufferedPosition: Long get() = currentPosition
    val duration: Long
    val isPlaying: Boolean
    val mediaItemCount: Int get() = 0
    var repeatMode: Int
    /** 随机模式（洗牌）；不支持 shuffle 的替身可忽略。 */
    var shuffleModeEnabled: Boolean
        get() = false
        set(_) {}
    val currentMediaItemIndex: Int
    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun replaceMediaItem(index: Int, item: MediaItem)
    /** 队列刷新专用：单项插入/删除（不断播）。ExoPlayer 原生支持，替换整表会打断当前播放故不用。 */
    fun addMediaItem(index: Int, item: MediaItem)
    fun removeMediaItem(index: Int)
    /** 按播放列表顺序跳到下一首/上一首（随机模式下由 ExoPlayer 决定落点）。 */
    fun seekToNextMediaItem() {}
    fun seekToPreviousMediaItem() {}
    /** 是否还有下一首（按洗牌序/循环模式判定）。默认 true：替身沿用旧的无回卷行为。 */
    val hasNextMediaItem: Boolean get() = true
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekTo(mediaItemIndex: Int, positionMs: Long) { seekTo(positionMs) }
    fun setPlaybackSpeed(speed: Float)
    fun addListener(listener: Player.Listener)
    fun release()

    /**
     * 等待底层播放器就绪（生产：MediaController 连接完成）。
     * 未就绪时写命令会被静默丢弃，播放前必须先等；Fake/无连接实现为即刻返回 true。
     * @return false = 超时仍未就绪，调用方应放弃本次播放并提示重试
     */
    suspend fun awaitReady(): Boolean = true
}
