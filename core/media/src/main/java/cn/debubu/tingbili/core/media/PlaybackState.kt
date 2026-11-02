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
    /**
     * 当前播放队列的来源名称，例如听单名、收藏或“播放历史”。
     * 单条播放（首页/搜索/详情直接播）没有来源时为 null；曲名用 [Track.videoTitle] 表达。
     */
    val sourceTitle: String? = null,
    /**
     * 队列来源的本地收藏夹 id（非空 = 队列跟随该收藏夹实时更新，
     * 见 [PlayerManager.syncFolderQueue]）；其它来源为 null。
     */
    val sourceFolderId: Long? = null,
    /** 当前播放列表的播放顺序（只属于这条队列） */
    val playOrder: PlayOrder = PlayOrder.FORWARD,
    /** 当前播放列表的随机开关（随机方式在播放设置里选） */
    val shuffleEnabled: Boolean = false,
    val positionMs: Long = 0L,
    /** 当前音源已缓存/已缓冲到的位置，用于播放页进度条底层展示。 */
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** 网络恢复自动重试已用完（上限）：播放页据此把错误升级为阻塞弹窗，等用户决策。 */
    val autoRetryExhausted: Boolean = false,
    val repeatMode: Int = REPEAT_MODE_OFF,
    val speed: Float = 1f,
    /**
     * 睡眠定时剩余秒数，null 表示关闭。绝对 deadline 落盘，杀进程可恢复（见 PlayerManager）。
     * 触发时暂停并落盘进度（见 [PlayerManager]），由播放页展示倒计时。
     */
    val sleepRemainSec: Int? = null,
    /** 倒计时总秒数（设定值，用于弹窗高亮当前生效项；结束/取消时清空） */
    val sleepTotalSec: Int? = null,
    /** 到点后播完本章再停（倒计时进行中的暂存开关；到点后转入播完即停阶段） */
    val sleepFinishChapter: Boolean = false,
    /** 播完即停阶段：倒计时已结束，等当前章播完就停 */
    val sleepEndOfChapter: Boolean = false,
) {
    companion object {
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
    }
}
