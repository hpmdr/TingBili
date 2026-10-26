package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 历史/进度单 tick：位置轮询与历史节流合并为一个循环。
 *
 * - 每 [POSITION_POLL_INTERVAL_MS] 读 transport 更新内存态（onTick，不碰磁盘）；
 * - 每 [HISTORY_SAVE_INTERVAL_MS] 落 Room + DataStore lastPosition（onPersistPosition 经调用方写）；
 * - 暂停/seek/切歌走 [flush] 即时写，保证退出/切后台不丢最后一秒；
 * - force 写用内存态 position（seek 刚落点 transport 还没跟上），不参与累计收听；
 * - 累计收听仅统计正常播放推进（排除 seek 跳变/换集），粒度约一个 tick。
 */
class HistoryTracker(
    private val transport: PlayerHandle,
    private val historyDao: HistoryDao,
) {
    interface Listener {
        fun currentTrack(): Track?
        /** 内存态 position（force 写用它）。 */
        fun statePositionMs(): Long
        /** 内存态更新：position/buffered/isPlaying，不碰磁盘。 */
        fun onTick(positionMs: Long, bufferedMs: Long)
        suspend fun onPersistPosition(pos: Long)
    }

    internal companion object {
        /** 历史落盘节流：10s 写一次 Room，1s 写是全 App 1Hz 抖动的根因 */
        const val HISTORY_SAVE_INTERVAL_MS = 10_000L
        /** 进度轮询：纯内存态更新 UI，不碰磁盘 */
        const val POSITION_POLL_INTERVAL_MS = 500L
    }

    // 本地累计收听时长统计：当前条目累计值 + 上次 tick 位置（排除 seek 跳变）
    private var listenAccumMs = 0L
    private var lastTickPos = -1L
    /** 历史按 BV 归属：同一 BV 换分 P 不算换条目，累计收听接着算 */
    private var lastListenBvid: String? = null
    private var lastSaveAtMs = 0L

    private var scope: CoroutineScope? = null
    private var listener: Listener? = null
    private var job: Job? = null

    fun attach(scope: CoroutineScope, listener: Listener) {
        job?.cancel()
        job = null
        this.scope = scope
        this.listener = listener
    }

    /** 开播时启动（重复调用=重开窗口）：节流窗口从此刻起算，累计收听按换集自动重载。 */
    fun start(nowMs: Long) {
        job?.cancel()
        lastSaveAtMs = nowMs
        job = scope?.launch {
            while (true) {
                delay(POSITION_POLL_INTERVAL_MS)
                val l = listener ?: break
                if (!transport.isPlaying) continue
                val pos = transport.currentPosition
                l.onTick(pos, transport.bufferedPosition)
                noteProgress(l, pos, force = false)
                // 暂停期间 continue 不推进 lastSaveAt：恢复后若已过期立刻补一次落盘
                if (clockMs() - lastSaveAtMs >= HISTORY_SAVE_INTERVAL_MS) {
                    saveNow(l, pos)
                    lastSaveAtMs = clockMs()
                }
            }
        }
    }

    /** 提供时钟：调用方注入（生产 wall clock，单测虚拟时间）。 */
    var clockMs: () -> Long = { System.currentTimeMillis() }

    /** 切歌/seek/暂停即时写（force）：不等节流，直接落盘。 */
    suspend fun flush() {
        val l = listener ?: return
        val track = l.currentTrack() ?: return
        val pos = l.statePositionMs()
        if (track.bvid != lastListenBvid) noteProgressKeyChanged(track, pos)
        saveNow(l, pos)
        lastSaveAtMs = clockMs()
    }

    /**
     * tick/force 共用的累计收听记账（无 IO，除了换 BV 时重载已有累计值）。
     * force 写不参与累计（seek 落点/切歌瞬间不算收听）。
     */
    private suspend fun noteProgress(l: Listener, pos: Long, force: Boolean) {
        val track = l.currentTrack() ?: return
        if (track.bvid != lastListenBvid) {
            noteProgressKeyChanged(track, pos)
        } else if (!force) {
            val delta = pos - lastTickPos
            if (delta in 1..(HISTORY_SAVE_INTERVAL_MS + 2000L)) listenAccumMs += delta
            lastTickPos = pos
        }
    }

    private suspend fun noteProgressKeyChanged(track: Track, pos: Long) {
        listenAccumMs = try {
            historyDao.get(track.bvid)?.listenDurationMs ?: 0L
        } catch (_: Exception) {
            0L
        }
        lastListenBvid = track.bvid
        lastTickPos = pos
    }

    private suspend fun saveNow(l: Listener, pos: Long) {
        val track = l.currentTrack() ?: return
        try {
            historyDao.save(
                HistoryEntity(
                    bvid = track.bvid,
                    cid = track.cid,
                    positionMs = pos,
                    updatedAt = System.currentTimeMillis(),
                    listenDurationMs = listenAccumMs,
                ),
            )
        } catch (_: Exception) {
        }
        try {
            l.onPersistPosition(pos)
        } catch (_: Exception) {
        }
    }

    fun release() {
        job?.cancel()
        job = null
    }
}
