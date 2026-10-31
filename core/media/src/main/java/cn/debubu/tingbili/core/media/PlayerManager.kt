package cn.debubu.tingbili.core.media

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlayQueueDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.RandomPlayMode
import cn.debubu.tingbili.core.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Queue owner + playback controller. Single source of truth for UI.
 * Audio-only: builds MediaItems via PlayUrlResolver, sets ExoPlayer.
 * History throttle 1s — periodic save while playing.
 * Step seek respects PreferencesRepository.stepSec.
 * 传输层为 PlayerHandle（生产经 MediaController），ExoPlayer 唯一释放权在 Service。
 */
@Singleton
class PlayerManager @Inject constructor(
    private val transport: PlayerHandle,
    private val historyDao: HistoryDao,
    private val prefs: PreferencesRepository,
    private val playUrls: PlayUrlResolver,
    private val catalog: CatalogResolver,
    private val artwork: ArtworkResolver,
    private val playUrlCache: PlayUrlCacheDao,
    private val playQueue: PlayQueueDao,
    @ApplicationContext private val appContext: Context,
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // ── 队列：唯一真相源在 QueueManager；这里只读镜像（赋值一律走 manager）──
    private val queueManager = QueueManager(playQueue, prefs)

    private val queueListener = object : QueueManager.Listener {
        override fun onQueueChanged(queue: List<Track>, index: Int, sourceTitle: String?) {
            _state.update {
                it.copy(
                    queue = queue,
                    currentIndex = index,
                    currentTrack = queue.getOrNull(index),
                    sourceTitle = sourceTitle,
                    sourceFolderId = queueManager.sourceFolderId,
                    playOrder = queueManager.playOrder,
                    shuffleEnabled = queueManager.shuffleEnabled,
                )
            }
        }
    }

    private val queue: List<Track> get() = queueManager.queue
    private val currentIndex: Int get() = queueManager.currentIndex

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Test hook — inject TestScope to control virtual time for throttle tests. */
    internal var testScope: CoroutineScope? = null
        set(value) {
            field = value
            // 睡眠任务跟随当前有效作用域：单测注入后切过去，避免旧 Main 作用域泄漏
            value?.let { sleepTimer.attach(it, sleepListener) }
            value?.let { historyTracker.attach(it, historyListener) }
            value?.let { queueManager.attach(it, queueListener) }
            value?.let { eventHandler.attach(it, eventListener) }
        }
    private fun effectiveScope(): CoroutineScope = testScope ?: scope
    /** Test hook — clock for sleep deadline; production=wall clock, tests inject virtual time. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 当前随机方式（播放设置里的全局偏好；开关本身属于当前队列）。 */
    @Volatile
    private var randomMode: RandomPlayMode = RandomPlayMode.SHUFFLE

    /** 纯随机自动切歌时，正在改跳到的目标下标（SEEK 回调用来清标记，避免递归）。 */
    private var randomRedirectTarget: Int = -1

    // ── 历史/进度：单 tick（位置轮询 + 历史节流合并），逻辑在 HistoryTracker ──
    private val historyTracker: HistoryTracker =
        HistoryTracker(
            transport,
            historyDao,
            underrunCount = { AudioPipelineMonitor.audioUnderruns.value },
        ).also { it.clockMs = { clock() } }

    private var lastRealignAtMs = 0L

    /**
     * 位置对表抑制窗口：刚 seek/play 后 MediaController 缓存仍可能是旧值，
     * 这段时间内不接受轮询采样，避免把字幕闪回旧位置。
     */
    private var positionSyncGraceUntilMs = 0L

    /**
     * 深度睡眠时钟：elapsedRealtime（含深度睡眠）- uptimeMillis（不含），
     * 两次采样的差值就是"设备真睡了多久"，不受改时钟影响。单测可注入。
     */
    internal var deepSleepClock: () -> Long = { SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() }

    /** 上次采样时的深度睡眠累计值；-1 = 尚未建立基线（首次调用不判定）。 */
    private var lastDeepSleepMs = -1L

    /** 上次可见时的 underrun 累计值；用它算增量，避免一次旧 underrun 长期给 realign 放行。 */
    private var lastVisibleUnderruns = -1L

    private val historyListener = object : HistoryTracker.Listener {
        override fun currentTrack(): Track? = _state.value.currentTrack
        override fun statePositionMs(): Long = _state.value.positionMs
        override fun sourceFolderId(): Long? = queueManager.sourceFolderId
        override fun sourceTitle(): String? = _state.value.sourceTitle

        /**
         * 进程被系统冻结期间 DSP 仍在出声，解冻后位置源可能落后于实际播放。
         * 同位置 seek 触发 AudioSink flush + AudioTrack 重建，让播放头重新向 HAL 取值，
         * 字幕/进度随后按正确位置重算。普通模式冻结会直接停音，不需要这一步。
         */
        override fun onPlaybackStalled(gapMs: Long, positionMs: Long) {
            // 只有 DSP offload 接管时，进程冻结才会出现「声音继续、位置没动」。
            // 用粘性标记：部分机型只回调 sleeping 不回调 offloaded，且 sleeping 只是瞬时值。
            val offloadInUse = AudioPipelineMonitor.offloadEverActive.value
            if (!offloadInUse) return
            if (realignCoolingDown()) return
            realignTo(transport.currentPosition, "after stall gap=${gapMs}ms")
        }

        /**
         * 暂停/缓冲态的兜底采样：只对表，不碰 isPlaying、不落盘、不计收听时长。
         * isPlaying 在缓冲/offload 出声时可能为假，位置却仍在推进。
         */
        override fun onPositionSampled(positionMs: Long) {
            applyPositionFromPlayer(positionMs)
        }
        override fun onTick(positionMs: Long, bufferedMs: Long) {
            // 纯内存态更新：持久化并入节流落盘，这里不再碰 DataStore
            _state.update {
                it.copy(
                    positionMs = positionMs,
                    bufferedPositionMs = bufferedMs,
                    isPlaying = true,
                )
            }
        }
        override suspend fun onPersistPosition(pos: Long) {
            prefs.setLastPosition(pos)
        }
    }
    // ── 直链管线：cid 补齐 + 惰性直链 item 组装（真正的 playurl 解析在 Service 的 DataSource 里）──
    private val playUrlPipeline = PlayUrlPipeline(catalog, artwork)

    private val playMutex = Mutex()

    // ── 睡眠定时：时间逻辑在 SleepTimer；PlayerManager 只做状态合入 + 到点暂停 ──
    private val sleepTimer: SleepTimer = SleepTimer(
        clock = { clock() },
        store = object : SleepTimer.Store {
            override suspend fun loadTail(): Boolean =
                try { prefs.sleepTail.first() } catch (_: Exception) { false }
            override suspend fun loadFinishChapter(fallback: Boolean): Boolean =
                try { prefs.sleepFinishChapter.first() } catch (_: Exception) { fallback }
            override suspend fun loadDeadlineMs(): Long =
                try { prefs.sleepDeadlineMs.first() } catch (_: Exception) { 0L }
            override suspend fun loadTotalSec(): Int =
                try { prefs.sleepTotalSec.first() } catch (_: Exception) { 0 }
            override suspend fun saveCountdown(deadlineMs: Long, totalSec: Int, finishChapter: Boolean) =
                prefs.saveSleepTimerState(deadlineMs, totalSec, finishChapter)
            override suspend fun saveTail() = prefs.saveSleepTailState()
            override suspend fun setFinishChapter(finishChapter: Boolean) =
                prefs.setSleepFinishChapter(finishChapter)
            override suspend fun clear() = prefs.clearSleepTimerState()
        },
    )

    private val sleepListener = object : SleepTimer.Listener {
        override fun chapter(): SleepTimer.ChapterSnapshot {
            val s = _state.value
            val key = s.currentTrack?.let { it.bvid to it.cid }
            // 章尾判定余量：距结束不足此时长即视为播完（与旧 watchChapterTail 一致）
            val over = s.durationMs > 0 && s.positionMs >= s.durationMs - SleepTimer.CHAPTER_END_MARGIN_MS
            return SleepTimer.ChapterSnapshot(key, over)
        }
        override fun onStateChanged(state: SleepTimer.SleepUiState) {
            _state.update {
                it.copy(
                    sleepRemainSec = state.remainSec,
                    sleepTotalSec = state.totalSec,
                    sleepFinishChapter = state.finishChapter,
                    sleepEndOfChapter = state.endOfChapter,
                )
            }
        }
        // pause() 内已落盘进度，满足"定时停止必须记好进度"；落盘清定时由 SleepTimer 先做
        override suspend fun onFire() = pause()
    }

    /**
     * 倒计时定时：seconds 秒后停止，60 秒起（恢复路径可小于 60，不钳制）。
     * 按绝对 deadline 走，Doze 冻结 delay 后能一次跳变追齐。
     * @param finishChapter 到点后是否播完本章再停（到点后转入播完即停阶段，不立即暂停）。
     */


    // ── 音频管线策略：offload×倍速互斥 + 开关镜像（选型/下发逻辑在组件内）──
    private val audioPolicy = AudioPipelinePolicy(prefs, transport)

    private val audioListener = object : AudioPipelinePolicy.Listener {
        override fun currentSpeed(): Float = _state.value.speed
        override fun onSpeedResetTo1x() { _state.update { it.copy(speed = 1f) } }
    }
    init {
        // 冷启动静默恢复上次队列与进度（暂停态，仅恢复 UI 状态，不自动播放）
        sleepTimer.attach(scope, sleepListener)
        historyTracker.attach(scope, historyListener)
        queueManager.attach(scope, queueListener)
        audioPolicy.attach(scope, audioListener)
        // 使用 scope 而非 effectiveScope：构造时 testScope 尚未来得及注入，测试可显式调用 restoreLastPlayback()
        scope.launch {
            restoreLastPlayback()
            restoreSleepTimer()
        }
        // 随机方式改了且当前队列开着随机：立即换下发策略（洗牌 / 纯随机）
        scope.launch {
            prefs.randomPlayMode.collect { mode ->
                randomMode = mode
                if (queueManager.shuffleEnabled) applyShuffleToTransport()
            }
        }
        scope.launch {
            try {
                val legacy = prefs.drainPlayUrlCache()
                if (legacy.isNotEmpty()) {
                    playUrlCache.putAll(legacy.map { (k, v) -> PlayUrlCacheEntity(k, v.url, v.fetchedAtMs) })
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 恢复上次播放的队列与进度到 UI 状态，暂停态；损坏/空队列时静默忽略 */
    suspend fun restoreLastPlayback() {
        val r = queueManager.restore() ?: return
        val track = r.queue.getOrNull(r.index) ?: return
        _state.update {
            it.copy(
                queue = r.queue,
                currentIndex = r.index,
                currentTrack = track,
                sourceTitle = r.sourceTitle,
                sourceFolderId = r.sourceFolderId,
                playOrder = r.playOrder,
                shuffleEnabled = r.shuffleEnabled,
                positionMs = r.positionMs,
                durationMs = track.durationMs,
                isPlaying = false,
                speed = r.speed,
                repeatMode = r.repeatMode
            )
        }
        randomMode = prefs.randomPlayMode.first()
        applyShuffleToTransport()
    }

    private fun persistPosition(pos: Long) {
        if (queue.isEmpty()) return
        effectiveScope().launch { prefs.setLastPosition(pos.coerceAtLeast(0L)) }
    }

    // ── 位置对表：字幕/进度只认播放器，不认可能被冻结过的缓存 ──

    /**
     * 把 UI 位置对表到播放器当前值。暂停/缓冲/播放都适用：
     * [PlayerHandle.isPlaying] 在缓冲、offload 出声、焦点抑制时不可靠，不能拿它当门槛。
     *
     * 未 prepare（冷启动仅恢复队列）、加载中、刚 seek/play 的抑制窗口内不采样，
     * 避免把 0 或旧值写回 UI。
     */
    internal fun syncPositionFromPlayer(force: Boolean = false): Long {
        if (transport.mediaItemCount == 0) return _state.value.positionMs
        return applyPositionFromPlayer(transport.currentPosition, force)
    }

    private fun applyPositionFromPlayer(p: Long, force: Boolean = false): Long {
        val s = _state.value
        if (s.currentTrack == null || s.isLoading) return s.positionMs
        if (transport.mediaItemCount == 0) return s.positionMs
        if (!force && clock() < positionSyncGraceUntilMs) return s.positionMs
        if (p < 0L || abs(p - s.positionMs) < POSITION_SYNC_EPS_MS) return s.positionMs
        _state.update { it.copy(positionMs = p) }
        return p
    }

    /** 冷却判定：0 表示本次进程还没 realign 过，不算冷却中。 */
    private fun realignCoolingDown(): Boolean =
        lastRealignAtMs != 0L && clock() - lastRealignAtMs < REALIGN_COOLDOWN_MS

    /**
     * UI 回到可见（解锁/回前台）：深度睡眠后唯一可靠的恢复点。
     *
     * 1. 先校正章节索引——夜间自动切章时进程可能被冻，transition 事件会晚到甚至丢；
     * 2. 再以播放器为准对表位置（字幕行号完全由它决定）；
     * 3. 仅当「确实深度睡过 + 正在播 + DSP 接管 + 有断流/失步证据」时同位置 seek，
     *    让 AudioTrack 重建、播放头重新向 HAL 取值。暂停态绝不 seek（起播本来就从播放器位置续）。
     */
    fun onUiVisible() {
        reconcileMediaItem()
        val beforeSync = _state.value.positionMs
        val sleptMs = consumeDeepSleepDelta()
        val underrunDelta = consumeUnderrunDelta()
        syncPositionFromPlayer()
        if (sleptMs < REALIGN_DEEP_SLEEP_MS) return
        if (!transport.isPlaying) return
        if (!AudioPipelineMonitor.offloadEverActive.value) return
        if (realignCoolingDown()) return
        val pos = _state.value.positionMs
        val mismatch = abs(pos - beforeSync)
        // 证据不足（这次睡眠期间没断流、状态与播放器也没差）就别制造断音
        if (mismatch < REALIGN_MISMATCH_MS && underrunDelta == 0L) return
        realignTo(pos, "on visible slept=${sleptMs}ms mismatch=${mismatch}ms underruns=$underrunDelta")
    }

    /** 本次可见与上次可见之间新增的 underrun 次数（断流硬证据）。 */
    private fun consumeUnderrunDelta(): Long {
        val now = AudioPipelineMonitor.audioUnderruns.value
        val prev = lastVisibleUnderruns
        lastVisibleUnderruns = now
        return if (prev < 0L) 0L else (now - prev).coerceAtLeast(0L)
    }

    /** 深度睡眠增量（本次调用与上次之间的真实睡眠时长），首次调用只建基线。 */
    private fun consumeDeepSleepDelta(): Long {
        val now = deepSleepClock()
        val prev = lastDeepSleepMs
        lastDeepSleepMs = now
        return if (prev < 0L) 0L else (now - prev).coerceAtLeast(0L)
    }

    /**
     * 以 transport 的当前项为准校正 UI 队列下标。
     * 夜间自动切章 + 进程冻结时，UI 可能还停在上一条，字幕随后会错章。
     */
    private fun reconcileMediaItem() {
        val q = queue
        // 起播准备中 state 是乐观值，transport 还没跟上，别反向覆盖
        if (q.isEmpty() || _state.value.isLoading || transport.mediaItemCount == 0) return
        val idx = transport.currentMediaItemIndex
        if (idx !in q.indices) return
        val s = _state.value
        if (idx != s.currentIndex) {
            queueManager.moveCurrentTo(idx)
        }
        if (idx != _state.value.currentIndex || _state.value.currentTrack?.queueKey() != q[idx].queueKey()) {
            _state.update { it.copy(currentIndex = idx, currentTrack = q[idx], durationMs = q[idx].durationMs) }
        }
    }

    /** 同位置 seek：AudioSink flush + AudioTrack 重建，让播放头重新向 HAL 取值。 */
    private fun realignTo(pos: Long, reason: String) {
        lastRealignAtMs = clock()
        Log.i(
            "TingBiliAudio",
            "realign($reason): pos=$pos underruns=${AudioPipelineMonitor.audioUnderruns.value}"
        )
        transport.seekTo(pos)
        positionSyncGraceUntilMs = clock() + POSITION_SYNC_GRACE_MS
        _state.update { it.copy(positionMs = pos) }
    }



    /** 队列刷新结果：给 Toast 文案用（added>0 报新增，否则报已是最新/已更新） */
    data class QueueRefreshResult(val added: Int, val removed: Int, val updated: Int)

    /**
     * 播放页“刷新当前内容”：B 站侧分 P 增减/改名后，不断播把队列对齐到最新。
     *
     * - 新增：解析 playurl 后插到新顺序对应位置（解析失败的跳过）。
     * - 删除：只删非在播项；在播的分 P 被删则原位保留（拍板语义），播完再消失。
     * - 改名/封面/时长：非在播项原位 replace；在播项只更新 UI 态（transport 里的还连着旧元数据，
     *   uri 没变不断播，下一切歌自然对齐）。
     * - transport 与 queue 对不上（理论上不会）时只更新 UI、不碰 transport，绝不崩。
     * - 与 play() 互斥（同一把锁），空新列表直接忽略。
     */
    suspend fun refreshQueue(
        newTracks: List<Track>,
        sourceTitle: String?,
        /** 队列来源收藏夹；BV 手动刷新等换了来源的场景传 null（默认清空跟随） */
        sourceFolderId: Long? = null,
    ): QueueRefreshResult =
        playMutex.withLock {
            if (newTracks.isEmpty()) return QueueRefreshResult(0, 0, 0)
            val title = sourceTitle?.takeIf { it.isNotBlank() }
            // 当前列表是倒序时，新内容也要按倒序参与对齐/落库
            val ordered = if (queueManager.playOrder == PlayOrder.REVERSE) newTracks.reversed() else newTracks
            val old = queue
            if (old.isEmpty() || currentIndex !in old.indices) {
                // 无有效队列：收下新列表等用户点（本来也没在播，不碰 transport）
                queueManager.adoptTracks(ordered, title, sourceFolderId)
                _state.update {
                    it.copy(
                        queue = ordered,
                        currentIndex = -1,
                        currentTrack = null,
                        sourceTitle = title,
                        sourceFolderId = sourceFolderId,
                    )
                }
                return QueueRefreshResult(0, 0, 0)
            }
            val plan = QueueRefreshPlanner.compute(old, currentIndex, ordered)
            val transportOk = try {
                transport.mediaItemCount == old.size
            } catch (_: Exception) {
                false
            }
            var added = 0
            if (transportOk) {
                // 删：倒序，只动非在播；删到当前下标之前的要同步挪 cur
                plan.deletions.forEach { i ->
                    try {
                        transport.removeMediaItem(i)
                    } catch (_: Exception) {
                    }
                }
                // 增/改：按最终顺序走一遍（B 站分 P 只增删不重排，走位必然对齐；
                // 万一重排，对不上就停手：UI 照新，transport 不再动，绝不插出重复项）
                val imageFormat = prefs.imageFormat.first()
                added = spliceTransport(plan, imageFormat)
            }
            // 队列真相唯一在 QueueManager（同步经 listener）；added 按新增 key 数报数，保持旧语义
            queueManager.applyRefresh(plan.final, plan.playingFinalIdx, title, sourceFolderId)
            return QueueRefreshResult(
                if (transportOk) added else plan.addedIfNoTransport,
                plan.removed,
                plan.updated,
            )
        }


    /**
     * transport 不断播手术：按最终顺序走一遍，base 对齐则原位 replace 元数据
     * （在播项不动，换 uri 会打断当前播放），新增项插空，返回实际插入数。
     * 重排（某项在 base 里但位置对不上）直接停手：UI 照新，transport 不再动。
     */
    private fun spliceTransport(
        plan: RefreshPlan,
        imageFormat: ImageFormat,
    ): Int {
        val expected = plan.base.toMutableList()
        var playIdx = plan.playingTransportIdx
        var added = 0
        for ((i, item) in plan.final.withIndex()) {
            val m = expected.getOrNull(i)
            if (m != null && m.queueKey() == item.queueKey()) {
                if (m != item && i != playIdx) {
                    // 元数据变了但直链要等真正开播才解析，直接换惰性 item 即可
                    try {
                        transport.replaceMediaItem(i, playUrlPipeline.buildItem(item, imageFormat))
                    } catch (_: Exception) {
                    }
                }
                continue
            }
            if (expected.any { it.queueKey() == item.queueKey() }) break // 重排：停手保平安
            try {
                transport.addMediaItem(i, playUrlPipeline.buildItem(item, imageFormat))
            } catch (_: Exception) {
                continue
            }
            expected.add(i, item)
            if (i <= playIdx) playIdx++
            added++
        }
        return added
    }

    /**
     * 收藏夹内容变化后把播放列表对齐到夹内最新内容（不断播）。
     *
     * - 只在当前队列来源就是 [folderId] 时生效；其它来源（搜索/历史/BV 分 P）不受影响。
     * - 增删/改名：复用 [refreshQueue] 的增量 splice，正在播的 P 不断。
     * - 拖拽排序：transport 的旧顺序已经错了，按最终顺序重建播放列表并回到原进度。
     * - 整夹被清空/删除：按 Media3 规范（删掉在播项且没有后继 → ended）停止播放并清空队列；
     *   停之前先落一次历史，重新收藏后还能续播。
     * - 只删在播的这一集、夹里还有别的：不打断当前声音，播完继续放剩下的。
     *
     * @return 是否真的改动了队列
     */
    suspend fun syncFolderQueue(
        folderId: Long,
        newTracks: List<Track>,
        folderName: String?,
    ): Boolean {
        if (queueManager.sourceFolderId != folderId) return false
        val old = queue
        if (currentIndex !in old.indices) return false
        val title = folderName?.takeIf { it.isNotBlank() } ?: _state.value.sourceTitle
        // 当前列表倒序时，收藏夹的新内容也按倒序对齐
        val ordered = if (queueManager.playOrder == PlayOrder.REVERSE) newTracks.reversed() else newTracks
        if (old.size == ordered.size && old.zip(ordered).all { (a, b) -> a == b }) {
            // 内容/顺序没变，只有夹名可能改了：只更新来源名，不动 transport
            if (title != _state.value.sourceTitle) {
                queueManager.applyRefresh(old, currentIndex, title, folderId)
            }
            return false
        }
        if (newTracks.isEmpty()) {
            playMutex.withLock {
                // 等待锁期间来源/队列可能已被别的 play 换掉
                if (queueManager.sourceFolderId != folderId) return false
                stopAndClearQueue()
            }
            return true
        }
        val plan = QueueRefreshPlanner.compute(old, currentIndex, ordered)
        val baseKeys = plan.base.map { it.queueKey() }
        val finalSurvivorKeys = plan.final.map { it.queueKey() }.filter { it in baseKeys }
        val reordered = finalSurvivorKeys != baseKeys
        if (!reordered) {
            refreshQueue(newTracks, title, folderId)
            return true
        }
        // 重排：transport 里的旧顺序已经和收藏夹对不上了，直接按最终顺序重建
        playMutex.withLock {
            // 等待锁期间来源/队列可能已被别的 play 换掉：放弃这次，等下一次夹变更再对齐
            if (queueManager.sourceFolderId != folderId || queue != old) return false
            val pos = _state.value.positionMs
            val wasPlaying = _state.value.isPlaying
            val imageFormat = prefs.imageFormat.first()
            queueManager.applyRefresh(plan.final, plan.playingFinalIdx, title, folderId)
            _state.update { it.copy(isPlaying = wasPlaying, positionMs = pos) }
            try {
                transport.setMediaItems(
                    plan.final.map { track -> playUrlPipeline.buildItem(track, imageFormat) },
                    plan.playingFinalIdx,
                    pos,
                )
                transport.prepare()
                if (wasPlaying) transport.play()
            } catch (e: Exception) {
                // 重建失败只影响 transport，队列真相已在 QueueManager；下次起播自然对齐
                Log.w("TingBiliPlay", "syncFolderQueue rebuild transport failed", e)
            }
        }
        return true
    }

    /**
     * 来源夹被清空/删除：先落历史，再停声、清 transport 与队列。
     * 队列无剩余项时进入 ended（Media3 对「删掉在播项且无后继」的规范行为）。
     */
    private suspend fun stopAndClearQueue() {
        runCatching { historyTracker.flush() }
        transport.pause()
        try {
            transport.setMediaItems(emptyList(), 0, 0L)
        } catch (e: Exception) {
            Log.w("TingBiliPlay", "clear transport failed", e)
        }
        queueManager.clearQueue()
        _state.update {
            it.copy(
                queue = emptyList(),
                currentIndex = -1,
                currentTrack = null,
                sourceTitle = null,
                sourceFolderId = null,
                playOrder = PlayOrder.FORWARD,
                shuffleEnabled = false,
                isPlaying = false,
                isLoading = false,
                positionMs = 0L,
                bufferedPositionMs = 0L,
                durationMs = 0L,
                errorMessage = null,
            )
        }
    }

    /**
     * 切换当前播放列表的正/倒序：只反转这条队列，当前在播项与进度保持不变。
     * 收藏夹/BV 的来源顺序不受影响。
     */
    suspend fun setPlayOrder(order: PlayOrder) = playMutex.withLock {
        if (order == queueManager.playOrder) return
        // 切顺序前先对表，避免用 500ms 前的缓存位置重建
        syncPositionFromPlayer(force = true)
        val pos = _state.value.positionMs
        val playing = _state.value.isPlaying
        queueManager.setPlayOrder(order)
        if (queue.isEmpty()) return
        rebuildTransport(queueManager.currentIndex, pos, playing)
    }

    /** 当前播放列表的随机开关（方式由播放设置决定，开关只属于这条队列）。 */
    suspend fun setShuffleEnabled(enabled: Boolean) = playMutex.withLock {
        randomMode = prefs.randomPlayMode.first()
        queueManager.setShuffleEnabled(enabled)
        applyShuffleToTransport()
        _state.update { it.copy(shuffleEnabled = enabled) }
    }

    /** 章节列表点某一集：在当前 transport 上跳，不重建队列、不改顺序。 */
    fun playAt(index: Int) {
        if (index !in queue.indices) return
        if (transport.mediaItemCount == queue.size) {
            transport.seekTo(index, 0L)
            transport.play()
            return
        }
        effectiveScope().launch {
            playMutex.withLock { rebuildTransport(index, 0L, startPlaying = true) }
        }
    }

    /** 根据当前队列/顺序/随机开关，把 transport 的 shuffle/repeat 状态对齐。 */
    private suspend fun applyShuffleToTransport() {
        val enabled = queueManager.shuffleEnabled
        val mode = randomMode
        transport.shuffleModeEnabled = enabled && mode == RandomPlayMode.SHUFFLE
        val prefRepeat = prefs.repeatMode.first()
        transport.repeatMode = when {
            // 纯随机需要「永远有下一首」：交给 repeat all，再由 auto 回调改跳随机项
            enabled && mode == RandomPlayMode.PURE_RANDOM -> Player.REPEAT_MODE_ALL
            prefRepeat == PlaybackState.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
            prefRepeat == PlaybackState.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * 用当前队列（已是正/倒序后的顺序）重建 transport，保持当前项与进度。
     * 用于冷恢复、切正/倒序、章节点播等不能重置队列状态的场景。
     */
    private suspend fun rebuildTransport(
        startIndex: Int = currentIndex,
        startPositionMs: Long = _state.value.positionMs,
        startPlaying: Boolean = _state.value.isPlaying,
    ) {
        val q = queue
        if (q.isEmpty()) return
        // 冷启动时 Service 可能还没起；先拉起前台服务再等 controller 就绪（幂等）
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, TingBiliPlaybackService::class.java),
        )
        if (!transport.awaitReady()) return
        val safe = startIndex.coerceIn(0, q.lastIndex)
        val pos = startPositionMs.coerceIn(
            0L,
            q[safe].durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
        randomMode = prefs.randomPlayMode.first()
        val imageFormat = prefs.imageFormat.first()
        transport.setMediaItems(
            q.map { track -> playUrlPipeline.buildItem(track, imageFormat) },
            safe,
            pos,
        )
        applyShuffleToTransport()
        val speed = _state.value.speed
        if (speed != 1f) transport.setPlaybackSpeed(speed)
        transport.prepare()
        if (startPlaying) transport.play()
        attachPlayerListener()
        historyTracker.start(clock())
        eventHandler.resetRetry()
        positionSyncGraceUntilMs = clock() + POSITION_SYNC_GRACE_MS
        queueManager.moveCurrentTo(safe)
        _state.update {
            it.copy(
                currentIndex = safe,
                currentTrack = q[safe],
                positionMs = pos,
                bufferedPositionMs = 0L,
                durationMs = q[safe].durationMs,
                isPlaying = startPlaying,
            )
        }
    }

    /** 纯随机：从当前列表随机取一首（可重复），手动/自动切歌都走这里。 */
    private fun jumpToRandom(excludeCurrent: Boolean) {
        val q = queue
        if (q.isEmpty()) return
        val candidates = q.indices.filter { !excludeCurrent || it != currentIndex }
        if (candidates.isEmpty()) return
        val target = candidates.random()
        if (transport.mediaItemCount == q.size) {
            transport.seekTo(target, 0L)
            transport.play()
        } else {
            effectiveScope().launch {
                playMutex.withLock { rebuildTransport(target, 0L, startPlaying = true) }
            }
        }
    }

    /**
     * Play queue starting at [index]. Builds MediaItems via PlayUrlResolver (audio-only),
     * calls transport.setMediaItems, prepare, play. Launches history save throttle 1s.
     * 加载期间 isLoading=true 且互斥，防止重复点击。
     */
    suspend fun play(
        tracks: List<Track>,
        index: Int,
        sourceTitle: String? = null,
        startPositionMs: Long? = null,
        /** 队列来源收藏夹 id：非空时队列会跟随该夹实时同步（[syncFolderQueue]） */
        sourceFolderId: Long? = null,
    ) {
        if (tracks.isEmpty()) return
        // 防重入：正在加载时忽略新的播放请求
        if (!playMutex.tryLock()) return
        // 旧状态留作失败回滚：解析失败时不能让界面停在「新标题 + 旧声音/旧进度」
        var previous: PlaybackState? = null
        var wasPlaying = false
        try {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            // 先拉起前台服务：后台保活 + 通知栏的前提；幂等，重复调用无副作用。
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, TingBiliPlaybackService::class.java)
            )
            // 冷启动时 MediaController 可能还没连上：这期间 setMediaItems 会被静默丢弃，
            // 表现就是「切了歌还在放上一集」。先等连接就绪，超时直接失败，不制造混合态。
            if (!transport.awaitReady()) {
                _state.update { it.copy(isLoading = false, errorMessage = "播放服务未就绪，请重试") }
                return
            }
            val safeIndex = index.coerceIn(0, tracks.lastIndex)

            // 解析直链要几秒，先把旧声停掉：否则界面已换成新曲目，耳朵里还是上一集。
            wasPlaying = transport.isPlaying
            if (wasPlaying) pause()
            previous = _state.value

            // 先改队列来源再乐观更新：后面解析 cid / 读图片格式会挂起，
            // 期间历史 tick 若看到“新曲目 + 旧收藏夹”会把新曲写成旧来源。
            val resolvedSourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
            queueManager.setSource(resolvedSourceTitle, sourceFolderId)

            // 乐观更新：URL 逐个解析很慢，先让播放页/mini-player 立刻显示队列归属。
            // 进度与时长一并换成新曲目的，避免「新标题配旧进度」。
            _state.update {
                it.copy(
                    queue = tracks,
                    currentIndex = safeIndex,
                    currentTrack = tracks.getOrNull(safeIndex),
                    sourceTitle = resolvedSourceTitle,
                    sourceFolderId = sourceFolderId,
                    playOrder = PlayOrder.FORWARD,
                    shuffleEnabled = false,
                    isPlaying = false,
                    positionMs = startPositionMs?.coerceAtLeast(0L) ?: 0L,
                    bufferedPositionMs = 0L,
                    durationMs = tracks[safeIndex].durationMs,
                )
            }

            // 搜索结果不带 cid（cid=0），先用 view() 解析出真实分P
            val resolved = tracks.map { t -> if (t.cid == 0L) playUrlPipeline.resolveCid(t) else t }

            // 直链不再在这里批量解析：item 存惰性 URI，播到哪一集才解析哪一集。
            // 这样 67 P 的视频不会一起打接口触发风控，也能立刻起播。
            val imageFormat = prefs.imageFormat.first()
            val startIndex = safeIndex
            val playableTracks = resolved
            val resolvedStartPosition = startPositionMs
                ?.coerceAtLeast(0L)
                ?.coerceAtMost(playableTracks[startIndex].durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
                ?: 0L

            // 持久化队列与起点，供下次冷启动恢复（Room 单行原子快照 + DataStore 进度）
            queueManager.setActiveQueue(
                playableTracks,
                startIndex,
                resolvedSourceTitle,
                sourceFolderId,
                playOrder = PlayOrder.FORWARD,
                shuffleEnabled = false,
            )
            persistPosition(resolvedStartPosition)
            // 新队列：顺序模式默认正序、随机开关默认关；把 transport 的旧随机状态清掉
            transport.shuffleModeEnabled = false
            transport.setMediaItems(
                playableTracks.map { track -> playUrlPipeline.buildItem(track, imageFormat) },
                startIndex,
                resolvedStartPosition,
            )
            transport.prepare()
            transport.play()

            // Restore preferences: speed（经 AudioPipelinePolicy：DSP 已接管时存量变速才钳回 1.0x）、repeatMode
            val speed = when (val r = audioPolicy.requestSpeed(prefs.speed.first())) {
                is AudioPipelinePolicy.SpeedResult.Applied -> r.speed
                AudioPipelinePolicy.SpeedResult.BlockedByOffload -> 1f
            }
            val repeat = prefs.repeatMode.first()
            if (speed != 1f) transport.setPlaybackSpeed(speed)
            transport.repeatMode = when (repeat) {
                PlaybackState.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                PlaybackState.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }

            _state.update {
                it.copy(
                    queue = playableTracks,
                    currentIndex = startIndex,
                    currentTrack = playableTracks[startIndex],
                    sourceTitle = resolvedSourceTitle,
                    sourceFolderId = sourceFolderId,
                    playOrder = PlayOrder.FORWARD,
                    shuffleEnabled = false,
                    isPlaying = true,
                    isLoading = false,
                    positionMs = resolvedStartPosition,
                    bufferedPositionMs = 0L,
                    durationMs = playableTracks[startIndex].durationMs,
                    repeatMode = repeat,
                    speed = speed,
                )
            }

            if (startPositionMs != null) {
                persistPosition(resolvedStartPosition)
            } else {
                // 自动续播：优先当前来源（收藏夹/BV）的历史；没有就取该分 P 最近一条
                val startTrack = playableTracks[startIndex]
                val saved = savedHistoryFor(startTrack, sourceFolderId)
                if (saved != null && saved.positionMs > 0L) {
                    transport.seekTo(saved.positionMs)
                    _state.update { it.copy(positionMs = saved.positionMs) }
                    persistPosition(saved.positionMs)
                }
            }

            historyTracker.start(clock())
            attachPlayerListener()
            eventHandler.resetRetry()
            // 刚起播：位置以 startPosition/历史落点为准，抑制采样直到播放器跟上
            positionSyncGraceUntilMs = clock() + POSITION_SYNC_GRACE_MS
        } catch (e: Exception) {
            revertToPrevious(previous, wasPlaying, e.message ?: "播放失败")
        } finally {
            // 确保加载态在异常或正常路径都收敛；若 play 已将 isLoading 置为 false 则保持
            if (_state.value.isLoading) {
                _state.update { it.copy(isLoading = false) }
            }
            if (playMutex.isLocked) playMutex.unlock()
        }
    }

    /**
     * 起播时的续播位置：
     * 1. 优先当前来源（收藏夹/BV）且就是这一集的记录；
     * 2. 否则取该分 P 跨来源最近一条（同一集从别处听过也能续上）。
     */
    private suspend fun savedHistoryFor(
        track: Track,
        sourceFolderId: Long?,
    ): HistoryEntity? {
        val source = try {
            if (sourceFolderId != null) {
                historyDao.getSource(HistoryEntity.SOURCE_FOLDER, sourceFolderId.toString())
            } else {
                historyDao.getSource(HistoryEntity.SOURCE_BV, track.bvid)
            }
        } catch (_: Exception) {
            null
        }
        if (source != null && source.bvid == track.bvid && source.cid == track.cid) return source
        return try {
            historyDao.getLatestForTrack(track.bvid, track.cid)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 起播失败：回到上一首的界面态并恢复原播放。
     * 两样都必须还原——否则会留下「界面是新曲目、声音和进度还是上一集」的错位状态。
     */
    private fun revertToPrevious(previous: PlaybackState?, wasPlaying: Boolean, message: String) {
        if (previous == null) {
            _state.update { it.copy(isPlaying = false, isLoading = false, errorMessage = message) }
            return
        }
        // 起播前已经把 QueueManager 的来源改成新队列；回滚时一并对齐，避免历史写入错来源
        queueManager.setSource(previous.sourceTitle, previous.sourceFolderId)
        _state.value = previous.copy(
            isLoading = false,
            isPlaying = wasPlaying,
            errorMessage = message,
        )
        if (wasPlaying) runCatching { transport.play() }
    }

    fun pause() {
        // 先对表再停：暂停落点必须以播放器为准。不能只看 isPlaying——
        // 缓冲/offload 出声时它为假，位置却已经走远，直接 stop 会把旧位置写进字幕与历史。
        // force：暂停是显式落点，必须接受播放器读数（抑制窗口只针对轮询采样）。
        syncPositionFromPlayer(force = true)
        transport.pause()
        _state.update { it.copy(isPlaying = false) }
        persistPosition(_state.value.positionMs)
        // 暂停即时刷一次历史：节流 10s 下，退出/切后台不再丢最后一段
        effectiveScope().launch { historyTracker.flush() }
    }

    fun startSleepTimer(seconds: Int, finishChapter: Boolean = false) =
        sleepTimer.start(seconds, finishChapter)

    /** 冷启动/进程重建后恢复定时：未来→继续倒计时；已过期→直接停或转尾；无落盘→忽略 */
    suspend fun restoreSleepTimer() = sleepTimer.restore()

    /**
     * 倒计时进行中改"到点后播完本章再停"开关。播完即停阶段关掉=整单取消
     *（否则关了开关定时还会停在章尾，不符合直觉）。
     */
    fun setSleepFinishChapter(v: Boolean) = sleepTimer.setFinishChapter(v)

    fun cancelSleepTimer() = sleepTimer.cancel()

    // currentKey 已收进 SleepTimer.Listener.chapter() 快照，此处不再需要

    fun resume() {
        // 起播前先对表：冷恢复分支拿的就是这个位置，宁可信播放器也不信被冻结过的缓存
        syncPositionFromPlayer()
        // 队列已恢复但 transport 尚未 prepare（冷启动后）：按当前顺序/随机状态重建并回原进度
        if (queue.isNotEmpty() && (transport.mediaItemCount == 0 || transport.currentMediaItemIndex !in queue.indices)) {
            val idx = currentIndex.coerceIn(0, queue.lastIndex)
            val pos = _state.value.positionMs
            effectiveScope().launch {
                playMutex.withLock { rebuildTransport(idx, pos, startPlaying = true) }
            }
            return
        }
        transport.play()
        _state.update { it.copy(isPlaying = true) }
        positionSyncGraceUntilMs = clock() + POSITION_SYNC_GRACE_MS
    }

    fun toggle() {
        if (transport.isPlaying) pause() else resume()
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, transport.duration.coerceAtLeast(0L).let { if (it == 0L) Long.MAX_VALUE else it }.coerceAtLeast(0L))
        // coerce to valid range — if duration unknown (0), just clamp at 0..MAX
        val clamped = target.coerceAtLeast(0L)
        transport.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
        positionSyncGraceUntilMs = clock() + POSITION_SYNC_GRACE_MS
        persistPosition(clamped)
        // seek 落点即时刷一次历史，进度页“上次听到”立刻对齐
        effectiveScope().launch { historyTracker.flush() }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(_state.value.positionMs + deltaMs)
    }

    /**
     * Step seek respects config [PreferencesRepository.stepSec].
     * dir: +1 forward, -1 backward.
     */
    suspend fun seekStep(dir: Int) {
        val stepSec = prefs.stepSec.first()
        val cur = transport.currentPosition
        val dur = transport.duration
        val max = if (dur > 0L && dur != androidx.media3.common.C.TIME_UNSET) dur else Long.MAX_VALUE
        val target = (cur + dir * stepSec * 1000L).coerceIn(0L, max)
        transport.seekTo(target)
        _state.update { it.copy(positionMs = target) }
        positionSyncGraceUntilMs = clock() + POSITION_SYNC_GRACE_MS
        persistPosition(target)
    }

    /**
     * offload 生效（DSP 真正接管）时不支持变速: 非 1.0x 请求直接拒绝, 不断播.
     * 开关打开瞬间（且 DSP 已接管）若正处变速, 由 AudioPipelinePolicy 一次性回 1.0x.
     * @return true=已应用；false=正被 DSP 接管拦截（调用方负责 Toast 提示，state 里不留内联错误）。
     */
    suspend fun setSpeed(speed: Float): Boolean {
        return when (val r = audioPolicy.requestSpeed(speed)) {
            is AudioPipelinePolicy.SpeedResult.Applied -> {
                _state.update { it.copy(speed = r.speed, errorMessage = null) }
                true
            }
            AudioPipelinePolicy.SpeedResult.BlockedByOffload -> false
        }
    }

    suspend fun setRepeatMode(mode: Int) {
        val clamped = mode.coerceIn(PlaybackState.REPEAT_MODE_OFF, PlaybackState.REPEAT_MODE_ALL)
        prefs.setRepeatMode(clamped)
        transport.repeatMode = when (clamped) {
            PlaybackState.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
            PlaybackState.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        _state.update { it.copy(repeatMode = clamped) }
    }

    fun next() {
        if (queue.isEmpty()) return
        if (queueManager.shuffleEnabled && randomMode == RandomPlayMode.PURE_RANDOM) {
            jumpToRandom(excludeCurrent = true)
            return
        }
        if (transport.mediaItemCount != queue.size) {
            val nextIdx = (currentIndex + 1) % queue.size
            effectiveScope().launch {
                playMutex.withLock { rebuildTransport(nextIdx, 0L, startPlaying = true) }
            }
            return
        }
        transport.seekToNextMediaItem()
        transport.play()
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (transport.mediaItemCount != queue.size) {
            val prevIdx = if (currentIndex - 1 < 0) queue.lastIndex else currentIndex - 1
            effectiveScope().launch {
                playMutex.withLock { rebuildTransport(prevIdx, 0L, startPlaying = true) }
            }
            return
        }
        transport.seekToPreviousMediaItem()
        transport.play()
    }

    fun release() {
        historyTracker.release()
        sleepTimer.release()
        audioPolicy.release()
        eventHandler.release()
    }

    // ── 播放器事件：切歌/播停/缓冲/错误 + 过期重试决策（计时与判定在组件内）──
    private val eventHandler = PlaybackEventHandler(transport, playUrlPipeline)

    private val eventListener = object : PlaybackEventHandler.Listener {
        override fun queueSnapshot(): List<Track> = queueManager.queue
        override fun onTransition(index: Int, reason: Int) {
            // 纯随机：自动连播先落到顺序下一首，立即改跳随机项（手动下一首走 next()）
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                randomRedirectTarget == -1 &&
                queueManager.shuffleEnabled &&
                randomMode == RandomPlayMode.PURE_RANDOM &&
                queue.size > 1 &&
                !_state.value.sleepEndOfChapter
            ) {
                val target = queue.indices.filter { it != index }.random()
                if (target != index) {
                    // 先把上一集在章尾的进度落到历史，再改跳
                    effectiveScope().launch { runCatching { historyTracker.flush() } }
                    randomRedirectTarget = target
                    transport.pause()
                    transport.seekTo(target, 0L)
                    transport.play()
                    return
                }
            }
            randomRedirectTarget = -1
            queueManager.moveCurrentTo(index)
            _state.update {
                it.copy(
                    currentIndex = index,
                    currentTrack = queue[index],
                    positionMs = transport.currentPosition,
                    bufferedPositionMs = transport.bufferedPosition,
                    durationMs = queue[index].durationMs,
                )
            }
            // 切歌即时刷一次历史：节流 10s 下，播完一切就落盘，不用等下一 tick
            effectiveScope().launch { historyTracker.flush() }
        }
        override fun onPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying, isLoading = false) }
            // 停下的落点以播放器为准（缓冲/offload 时 isPlaying 会假，位置才是真相）
            if (!isPlaying) {
                positionSyncGraceUntilMs = 0L
                syncPositionFromPlayer()
            }
        }
        override fun onPlayerStateChanged(buffering: Boolean) {
            _state.update {
                it.copy(
                    isPlaying = transport.isPlaying,
                    isLoading = buffering,
                    bufferedPositionMs = transport.bufferedPosition,
                )
            }
            // READY 后位置可信；BUFFERING 期间位置可能还在推进，交给轮询低频采样
            if (!buffering) syncPositionFromPlayer()
        }
        override fun onPositionDiscontinuity() {
            // 播放器权威事件（seek/切章/自动跳变）到达：解除抑制并按它对齐
            positionSyncGraceUntilMs = 0L
            syncPositionFromPlayer()
        }
        override fun onFatalError(message: String?) {
            _state.update { it.copy(isLoading = false, errorMessage = message ?: "播放出错") }
        }
        override fun onRetryStarted() {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
        }
        override suspend fun onRetryRecover(): PlaybackEventHandler.RecoverResult {
            val track = _state.value.currentTrack
                ?: return PlaybackEventHandler.RecoverResult.Failed("音源失效")
            val pos = _state.value.positionMs.coerceAtLeast(transport.currentPosition)
            val idx = currentIndex
            if (idx !in queue.indices) {
                return PlaybackEventHandler.RecoverResult.Failed("刷新失败")
            }
            // 惰性直链：失效旧档缓存，换惰性 item 再 prepare，DataSource 打开时按当前偏好重新解析
            val imageFormat = prefs.imageFormat.first()
            runCatching { playUrlCache.delete("${track.bvid}:${track.cid}") }
            transport.replaceMediaItem(idx, playUrlPipeline.buildItem(track, imageFormat))
            transport.prepare()
            transport.seekTo(pos)
            transport.play()
            _state.update { it.copy(isLoading = true) }
            return PlaybackEventHandler.RecoverResult.Recovered
        }
    }
    /**
     * 音质切换：偏好已由调用方先落盘；换回惰性 item 并 prepare，
     * DataSource 打开时按新偏好重新解析直链（沿用 onRetryRecover 的不断播 swap 模式）。
     * 无在播返回 false；正常返回 true。
     */
    suspend fun refreshCurrentUrl(): Boolean {
        val track = _state.value.currentTrack ?: return false
        val pos = _state.value.positionMs.coerceAtLeast(transport.currentPosition)
        val idx = currentIndex
        if (idx !in queue.indices) return false
        // 旧档直链可能还是"新鲜"缓存，必须失效，否则换音质后仍复用旧 URL
        runCatching { playUrlCache.delete("${track.bvid}:${track.cid}") }
        transport.replaceMediaItem(idx, playUrlPipeline.buildItem(track, prefs.imageFormat.first()))
        transport.prepare()
        transport.seekTo(pos)
        if (_state.value.isPlaying) transport.play()
        return true
    }

    private companion object {
        /** 冻结后重同步的冷却：seek 会重建 AudioTrack，别让它连续触发 */
        const val REALIGN_COOLDOWN_MS = 60_000L
        /** 位置对表死区：小于它不写回，避免 tick 抖动带来无意义重组 */
        const val POSITION_SYNC_EPS_MS = 200L
        /** seek/play 后暂停采样的抑制窗口：控制器缓存可能还没跟上 */
        const val POSITION_SYNC_GRACE_MS = 1_000L
        /** 深度睡眠超过它才考虑重建音频路径（暂停一晚不算：不 seek） */
        const val REALIGN_DEEP_SLEEP_MS = 60_000L
        /** 状态位置与播放器位置差超过它才算真失步 */
        const val REALIGN_MISMATCH_MS = 1_000L
    }

    private var listenerAttached = false
    private fun attachPlayerListener() {
        if (listenerAttached) return
        listenerAttached = true
        eventHandler.attach(effectiveScope(), eventListener)
    }
}
