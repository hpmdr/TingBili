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
        /**
         * 非播放态（暂停/缓冲）低频采样：只用于位置对表。
         * 不改 isPlaying、不落盘、不计收听时长——调用方自行判断是否采纳。
         */
        fun onPositionSampled(positionMs: Long) {}
        suspend fun onPersistPosition(pos: Long)

        /**
         * 当前队列来源收藏夹 id：只有“从收藏夹播放”时非空；
         * 搜索/详情/BV 页/历史续播等返回 null。
         */
        fun sourceFolderId(): Long? = null

        /** 当前队列来源标题（收藏夹名）；非收藏夹来源可为 null。 */
        fun sourceTitle(): String? = null

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
        /** 暂停/缓冲态位置采样间隔（tick 数）：一次读取，无落盘无重组，成本可忽略 */
        const val PAUSED_SAMPLE_EVERY_TICKS = 4
    }

    // 本地累计收听时长统计：当前播单累计值 + 上次 tick 位置（排除 seek 跳变）
    private var listenAccumMs = 0L
    private var lastTickPos = -1L
    /** 当前播单来源（BV/收藏夹）+ 分集：同一播单换 P 保留累计，换播单重新载入 */
    private var lastSourceKey: String? = null
    private var lastListenBvid: String? = null
    private var lastListenCid: Long? = null
    private var lastSaveAtMs = 0L
    // 冻结检测：上一次 tick 的墙钟与播放位置（与「累计收听」的 lastTickPos 分开，避免互相污染）
    private var lastTickWallMs = 0L
    private var lastPosForStall = -1L
    /** 上次 tick 看到的 underrun 累计值，用于识别「本 tick 有新 underrun」 */
    private var lastSeenUnderruns = 0L
    /** 暂停/缓冲态采样节流计数 */
    private var pausedSampleTicks = 0

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
        // 重开窗口：下一 tick 重新按当前播单载入已累计时长
        lastSourceKey = null
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
                    // 暂停/缓冲：isPlaying 在缓冲、offload 出声时可能为假，位置却仍在推进。
                    // 低频采样只做位置对表（不落盘、不计收听、不改 isPlaying），让 UI 位置
                    // 在暂停/缓冲期间也能自愈，而不是一直停在冻结前的旧值。
                    if (pausedSampleTicks++ % PAUSED_SAMPLE_EVERY_TICKS == 0) {
                        l.onPositionSampled(transport.currentPosition)
                    }
                    lastPosForStall = -1L
                    continue
                }
                pausedSampleTicks = 0
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
                val track = l.currentTrack() ?: continue
                val source = sourceOf(l, track)
                noteProgress(l, track, pos, force = false, source = source)
                // 暂停期间 continue 不推进 lastSaveAt：恢复后若已过期立刻补一次落盘
                if (clockMs() - lastSaveAtMs >= HISTORY_SAVE_INTERVAL_MS) {
                    saveNow(l, track, pos, source)
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
        // 来源/曲目在 flush 开始时捕获，避免 await IO 期间队列切换后把旧曲写成新来源
        val source = sourceOf(l, track)
        // force 不累计 delta，但会在来源/分集变化时重载播单累计值
        noteProgress(l, track, pos, force = true, source = source)
        saveNow(l, track, pos, source)
        lastSaveAtMs = clockMs()
    }

    /**
     * tick/force 共用的累计收听记账。
     * - 换播单：从该播单历史行载入已累计时长；
     * - 同播单换 P：保留播单累计，只重置位置基线；
     * - force 写不参与累计（seek 落点/切歌瞬间不算收听）。
     */
    private suspend fun noteProgress(
        l: Listener,
        track: Track,
        pos: Long,
        force: Boolean,
        source: Source,
    ) {
        val key = "${source.type}:${source.id}"
        if (key != lastSourceKey) {
            listenAccumMs = try {
                historyDao.getSource(source.type, source.id)?.listenDurationMs ?: 0L
            } catch (_: Exception) {
                0L
            }
            lastSourceKey = key
            lastListenBvid = track.bvid
            lastListenCid = track.cid
            lastTickPos = pos
            return
        }
        if (track.bvid != lastListenBvid || track.cid != lastListenCid) {
            // 同一播单换分集：累计时长接着算，但位置 delta 必须从新集基线开始
            lastListenBvid = track.bvid
            lastListenCid = track.cid
            lastTickPos = pos
            return
        }
        if (!force) {
            val delta = pos - lastTickPos
            if (delta in 1..(HISTORY_SAVE_INTERVAL_MS + 2000L)) listenAccumMs += delta
            lastTickPos = pos
        }
    }

    /** 当前播单来源：收藏夹优先，否则归到当前 BV。 */
    private fun sourceOf(l: Listener, track: Track): Source {
        val folderId = l.sourceFolderId()
        return if (folderId != null) {
            Source(
                type = HistoryEntity.SOURCE_FOLDER,
                id = folderId.toString(),
                title = l.sourceTitle()?.takeIf { it.isNotBlank() },
            )
        } else {
            Source(
                type = HistoryEntity.SOURCE_BV,
                id = track.bvid,
                title = track.videoTitle?.takeIf { it.isNotBlank() } ?: track.title,
            )
        }
    }

    private data class Source(val type: Int, val id: String, val title: String?)

    private suspend fun saveNow(l: Listener, track: Track, pos: Long, source: Source) {
        try {
            historyDao.save(
                HistoryEntity(
                    bvid = track.bvid,
                    cid = track.cid,
                    positionMs = pos,
                    updatedAt = System.currentTimeMillis(),
                    listenDurationMs = listenAccumMs,
                    sourceType = source.type,
                    sourceId = source.id,
                    sourceTitle = source.title,
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
