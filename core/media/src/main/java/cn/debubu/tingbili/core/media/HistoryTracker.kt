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
    /** underrun 累计值（offload 断流证据）；默认恒 0，单测不传即只走墙钟判定 */
    private val underrunCount: () -> Long = { 0L },
) {
    interface Listener {
        fun currentTrack(): Track?
        /** 内存态 position（force 写用它）。 */
        fun statePositionMs(): Long
        /** 内存态更新：position/buffered/isPlaying，不碰磁盘。 */
        fun onTick(positionMs: Long, bufferedMs: Long)
        suspend fun onPersistPosition(pos: Long)

        /**
         * 检测到「墙钟流逝 ≫ 播放位置前进」：进程被系统冻结/深度睡眠期间
         * DSP 仍独立推进，恢复后位置源可能已和实际出声脱节，调用方决定是否重同步。
         */
        fun onPlaybackStalled(gapMs: Long, positionMs: Long) {}
    }

    internal companion object {
        /** 历史落盘节流：10s 写一次 Room，1s 写是全 App 1Hz 抖动的根因 */
        const val HISTORY_SAVE_INTERVAL_MS = 10_000L
        /** 进度轮询：纯内存态更新 UI，不碰磁盘 */
        const val POSITION_POLL_INTERVAL_MS = 500L
        /** 两次 tick 的墙钟间隔超过它，才怀疑进程被冻结（Doze 下延迟通常只有几秒） */
        const val STALL_GAP_THRESHOLD_MS = 10_000L
        /** 播放位置前进比墙钟落后超过它，判定位置源与 DSP 脱节 */
        const val STALL_LAG_THRESHOLD_MS = 3_000L
        /** 有 underrun 佐证时放宽：进程可能只是被限流，墙钟间隔远不到 10s */
        const val STALL_GAP_THRESHOLD_UNDERRUN_MS = 3_000L
        const val STALL_LAG_THRESHOLD_UNDERRUN_MS = 1_500L
    }

    // 本地累计收听时长统计：当前条目累计值 + 上次 tick 位置（排除 seek 跳变）
    private var listenAccumMs = 0L
    private var lastTickPos = -1L
    /** 历史按 BV 归属：同一 BV 换分 P 不算换条目，累计收听接着算 */
    private var lastListenBvid: String? = null
    private var lastSaveAtMs = 0L
    // 冻结检测：上一次 tick 的墙钟与播放位置（与「累计收听」的 lastTickPos 分开，避免互相污染）
    private var lastTickWallMs = 0L
    private var lastPosForStall = -1L
    /** 上次 tick 看到的 underrun 累计值，用于识别「本 tick 有新 underrun」 */
    private var lastSeenUnderruns = 0L

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
        lastTickWallMs = nowMs
        lastPosForStall = -1L
        // 开播瞬间同步基线：之前累积的 underrun 不算本轮证据
        lastSeenUnderruns = underrunCount()
        job = scope?.launch {
            while (true) {
                delay(POSITION_POLL_INTERVAL_MS)
                val l = listener ?: break
                val now = clockMs()
                val gap = if (lastTickWallMs > 0L) now - lastTickWallMs else 0L
                lastTickWallMs = now
                if (!transport.isPlaying) {
                    lastPosForStall = -1L
                    continue
                }
                val pos = transport.currentPosition
                // 第二路信号：offload 缓冲被 HAL 抽空会记 underrun，说明确实断过。
                // 有它佐证时放宽阈值——可能只是被限流，墙钟间隔远不到 10s。
                val underruns = underrunCount()
                val underrunHint = underruns > lastSeenUnderruns
                lastSeenUnderruns = underruns
                val gapThreshold =
                    if (underrunHint) STALL_GAP_THRESHOLD_UNDERRUN_MS else STALL_GAP_THRESHOLD_MS
                val lagThreshold =
                    if (underrunHint) STALL_LAG_THRESHOLD_UNDERRUN_MS else STALL_LAG_THRESHOLD_MS
                // 墙钟走了很久、位置却几乎没动：进程被冻结期间 DSP 自己在放，位置源已脱节
                if (gap >= gapThreshold && lastPosForStall >= 0L &&
                    gap - (pos - lastPosForStall) >= lagThreshold
                ) {
                    l.onPlaybackStalled(gap, pos)
                }
                lastPosForStall = pos
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
