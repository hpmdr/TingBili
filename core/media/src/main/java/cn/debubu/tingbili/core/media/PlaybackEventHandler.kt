package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放器事件处理：ExoPlayer 回调（切歌/播停/缓冲/错误）+ 直链过期重试决策。
 *
 * - 只做决策与计时：transport 操作与状态合入走 [Listener] 回到调用方（PlayerManager）；
 * - 重试上限 [MAX_URL_REFRESH_RETRY]，退避 delay(1000ms * 次数)，虚拟时间可测；
 * - 切歌/恢复播/READY 即清重试计数（与旧逻辑一致）。
 */
class PlaybackEventHandler(
    private val transport: PlayerHandle,
    private val pipeline: PlayUrlPipeline,
) {
    /** 重试恢复结果：调用方执行替换播控，成功仅报 Recovered（细节调用方自理）。 */
    sealed interface RecoverResult {
        data object Recovered : RecoverResult
        data class Failed(val message: String) : RecoverResult
    }

    interface Listener {
        /** 当前队列快照（transition 越界保护用）。 */
        fun queueSnapshot(): List<Track>
        /** 切歌：同步队列/状态/落盘/重排主动刷新（重试计数由本类自清）。 */
        fun onTransition(index: Int)
        fun onPlayingChanged(isPlaying: Boolean)
        fun onPlayerStateChanged(buffering: Boolean)
        /** 播放器位置不连续（seek/切章/自动跳变）：调用方按播放器位置立即对表。 */
        fun onPositionDiscontinuity() {}
        fun onFatalError(message: String?)
        fun onRetryStarted()
        /** 执行一次刷新恢复（解析→替换→prepare→seek→play），调用方返回结果。 */
        suspend fun onRetryRecover(): RecoverResult
    }

    internal companion object {
        const val MAX_URL_REFRESH_RETRY = 2
    }

    private var scope: CoroutineScope? = null
    private var listener: Listener? = null
    private var retryJob: Job? = null
    private var listenerAdded = false

    var retryCount = 0
        private set

    fun resetRetry() {
        retryCount = 0
    }

    fun attach(scope: CoroutineScope, listener: Listener) {
        this.scope = scope
        this.listener = listener
        retryJob?.cancel()
        retryJob = null
        if (listenerAdded) return
        listenerAdded = true
        transport.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = transport.currentMediaItemIndex
                if (idx in (listener.queueSnapshot().indices)) {
                    resetRetry()
                    listener.onTransition(idx)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener.onPlayingChanged(isPlaying)
                if (isPlaying) resetRetry()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // keep isPlaying in sync + 驱动加载态：buffering 时显示加载，ready/idle/ended 时收敛
                val buffering = state == Player.STATE_BUFFERING
                listener.onPlayerStateChanged(buffering)
                if (state == Player.STATE_READY) resetRetry()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                listener.onPositionDiscontinuity()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!pipeline.isRecoverable(error) || retryCount >= MAX_URL_REFRESH_RETRY) {
                    listener.onFatalError(error.message ?: "播放出错")
                    return
                }
                retryCount++
                listener.onRetryStarted()
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(1000L * retryCount)
                    when (val r = listener.onRetryRecover()) {
                        is RecoverResult.Recovered -> Unit // 调用方已接管（isLoading/重排主动刷新）
                        is RecoverResult.Failed -> listener.onFatalError(r.message)
                    }
                }
            }
        })
    }

    fun release() {
        retryJob?.cancel()
        retryJob = null
    }
}
