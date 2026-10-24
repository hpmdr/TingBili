package cn.debubu.tingbili.core.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 睡眠定时：纯时间逻辑，不依赖 Android/ExoPlayer/DataStore 具体实现。
 *
 * - 绝对 deadline + 存储落盘：杀进程/Doze 冻结后可恢复校准，触发=调用方暂停。
 * - 时间源 [clock] 与持久化 [Store] 均由调用方注入，单测用虚拟时间 + 内存 Store。
 * - 状态上报走 [Listener.onStateChanged]，调用方（如 PlayerManager）原样合入 UI 态；
 *   到点动作走 [Listener.onFire]（调用方 pause + 落盘进度）。
 */
class SleepTimer(
    private val clock: () -> Long,
    private val store: Store,
) {
    /** 持久化口：调用方用 PreferencesRepository 实现，读失败给缺省（与旧逻辑一致）。 */
    interface Store {
        suspend fun loadTail(): Boolean
        suspend fun loadFinishChapter(fallback: Boolean): Boolean
        suspend fun loadDeadlineMs(): Long
        suspend fun loadTotalSec(): Int
        suspend fun saveCountdown(deadlineMs: Long, totalSec: Int, finishChapter: Boolean)
        suspend fun saveTail()
        suspend fun setFinishChapter(finishChapter: Boolean)
        suspend fun clear()
    }

    /** 章快照：调用方提供当前章 key 与"是否已播完"（距结束不足余量即视为播完）。 */
    data class ChapterSnapshot(val key: Pair<String, Long>?, val isOver: Boolean)

    /** 睡眠定时 UI 状态（字段与 PlaybackState 睡眠四字段一一对应）。 */
    data class SleepUiState(
        val remainSec: Int? = null,
        val totalSec: Int? = null,
        val finishChapter: Boolean = false,
        val endOfChapter: Boolean = false,
    )

    interface Listener {
        fun chapter(): ChapterSnapshot
        fun onStateChanged(state: SleepUiState)
        suspend fun onFire()
    }

    internal companion object {
        /** 章尾判定余量：距结束不足此时长即视为播完 */
        const val CHAPTER_END_MARGIN_MS = 1500L
    }

    var state = SleepUiState()
        private set

    private var scope: CoroutineScope? = null
    private var listener: Listener? = null
    private var job: Job? = null

    /** 绑定协程作用域与监听；重复绑定先取消旧任务（单测 testScope 切换时用）。 */
    fun attach(scope: CoroutineScope, listener: Listener) {
        job?.cancel()
        job = null
        this.scope = scope
        this.listener = listener
    }

    private fun emit(s: SleepUiState) {
        state = s
        listener?.onStateChanged(s)
    }

    /**
     * 倒计时定时：seconds 秒后停止，60 秒起（恢复路径可小于 60，不钳制）。
     * 按绝对 deadline 走，每秒用 clock() 重算剩余，Doze 冻结 delay 后能一次跳变追齐。
     * @param finishChapter 到点后是否播完本章再停（到点后转入播完即停阶段，不立即暂停）。
     */
    fun start(seconds: Int, finishChapter: Boolean = false) {
        job?.cancel()
        val total = seconds.coerceAtLeast(60)
        val deadline = clock() + total * 1000L
        emit(SleepUiState(remainSec = total, totalSec = total, finishChapter = finishChapter, endOfChapter = false))
        scope?.launch {
            try {
                store.saveCountdown(deadline, total, finishChapter)
            } catch (_: Exception) {
            }
        }
        job = scope?.launch { launchCountdown(deadline) }
    }

    /** 冷启动/进程重建后恢复定时：未来→继续倒计时；已过期→直接停或转尾；无落盘→忽略 */
    suspend fun restore() {
        try {
            val tail = try {
                store.loadTail()
            } catch (_: Exception) {
                false
            }
            if (tail) {
                val finish = try {
                    store.loadFinishChapter(true)
                } catch (_: Exception) {
                    true
                }
                if (listener?.chapter()?.key == null) {
                    try {
                        store.clear()
                    } catch (_: Exception) {
                    }
                    return
                }
                emit(SleepUiState(remainSec = null, totalSec = null, finishChapter = finish, endOfChapter = true))
                job?.cancel()
                val key = listener?.chapter()?.key ?: return
                job = scope?.launch { watchChapterTail(key) }
                return
            }
            val deadline = try {
                store.loadDeadlineMs()
            } catch (_: Exception) {
                0L
            }
            if (deadline <= 0L) return
            val total = try {
                store.loadTotalSec()
            } catch (_: Exception) {
                0
            }
            val finish = try {
                store.loadFinishChapter(false)
            } catch (_: Exception) {
                false
            }
            val remain = ((deadline - clock()) / 1000).toInt()
            if (remain <= 0) {
                if (finish) enterChapterTail() else fire()
                return
            }
            emit(SleepUiState(remainSec = remain, totalSec = total.coerceAtLeast(remain), finishChapter = finish, endOfChapter = false))
            job?.cancel()
            job = scope?.launch { launchCountdown(deadline) }
        } catch (_: Exception) {
        }
    }

    /** 倒计时主循环：按 deadline 重算剩余，抗 delay 被系统拉长 */
    private suspend fun launchCountdown(deadlineMs: Long) {
        while (true) {
            delay(1000)
            val remain = ((deadlineMs - clock()) / 1000).toInt()
            if (remain <= 0) break
            emit(state.copy(remainSec = remain))
        }
        if (state.finishChapter) enterChapterTail() else fire()
    }

    /**
     * 倒计时进行中改"到点后播完本章再停"开关。播完即停阶段关掉=整单取消
     *（否则关了开关定时还会停在章尾，不符合直觉）。
     */
    fun setFinishChapter(v: Boolean) {
        if (state.endOfChapter) {
            if (!v) cancel()
            return
        }
        if (state.remainSec != null) {
            emit(state.copy(finishChapter = v))
            scope?.launch {
                try {
                    store.setFinishChapter(v)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 倒计时到点 + 开关开：转入播完即停阶段。切歌或播到尾时暂停；手动暂停不取消（章还没完） */
    private suspend fun enterChapterTail() {
        val key = listener?.chapter()?.key
        if (key == null) {
            fire()
            return
        }
        emit(state.copy(remainSec = null, totalSec = null, endOfChapter = true))
        scope?.launch {
            try {
                store.saveTail()
            } catch (_: Exception) {
            }
        }
        job?.cancel()
        job = scope?.launch { watchChapterTail(key) }
    }

    private suspend fun watchChapterTail(key: Pair<String, Long>) {
        while (true) {
            delay(1000)
            val snapshot = listener?.chapter() ?: ChapterSnapshot(null, true)
            val chapterOver = snapshot.key != key || snapshot.isOver
            if (chapterOver) {
                fire()
                return
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        scope?.launch {
            try {
                store.clear()
            } catch (_: Exception) {
            }
        }
        emit(SleepUiState())
    }

    private suspend fun fire() {
        scope?.launch {
            try {
                store.clear()
            } catch (_: Exception) {
            }
        }
        try {
            listener?.onFire()
        } finally {
            emit(SleepUiState())
        }
    }

    fun release() {
        job?.cancel()
        job = null
    }
}
