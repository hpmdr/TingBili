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
    val currentMediaItemIndex: Int
    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun replaceMediaItem(index: Int, item: MediaItem)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekTo(mediaItemIndex: Int, positionMs: Long) { seekTo(positionMs) }
    fun setPlaybackSpeed(speed: Float)
    fun addListener(listener: Player.Listener)
    fun release()
}
