package cn.debubu.tingbili.core.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlayQueueDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        }
    private fun effectiveScope(): CoroutineScope = testScope ?: scope
    /** Test hook — clock for sleep deadline; production=wall clock, tests inject virtual time. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // ── 历史/进度：单 tick（位置轮询 + 历史节流合并），逻辑在 HistoryTracker ──
    private val historyTracker: HistoryTracker =
        HistoryTracker(transport, historyDao).also { it.clockMs = { clock() } }

    private val historyListener = object : HistoryTracker.Listener {
        override fun currentTrack(): Track? = _state.value.currentTrack
        override fun statePositionMs(): Long = _state.value.positionMs
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
    // ── 直链管线：cid 补齐 + 解析 + 组装 + 刷新（类 MediaSource 数据源层）──
    private val playUrlPipeline = PlayUrlPipeline(playUrls, catalog, artwork, playUrlCache)

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
                positionMs = r.positionMs,
                durationMs = track.durationMs,
                isPlaying = false,
                speed = r.speed,
                repeatMode = r.repeatMode
            )
        }
    }

    private fun persistPosition(pos: Long) {
        if (queue.isEmpty()) return
        effectiveScope().launch { prefs.setLastPosition(pos.coerceAtLeast(0L)) }
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
    suspend fun refreshQueue(newTracks: List<Track>, sourceTitle: String?): QueueRefreshResult =
        playMutex.withLock {
            if (newTracks.isEmpty()) return QueueRefreshResult(0, 0, 0)
            val title = sourceTitle?.takeIf { it.isNotBlank() }
            val old = queue
            if (old.isEmpty() || currentIndex !in old.indices) {
                // 无有效队列：收下新列表等用户点（本来也没在播，不碰 transport）
                queueManager.adoptTracks(newTracks, title)
                _state.update { it.copy(queue = newTracks, currentIndex = -1, currentTrack = null, sourceTitle = title) }
                return QueueRefreshResult(0, 0, 0)
            }
            val plan = QueueRefreshPlanner.compute(old, currentIndex, newTracks)
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
                val urlMap = playUrlPipeline.resolveUrls(plan.addedTracks)
                val imageFormat = prefs.imageFormat.first()
                val urlCache = try {
                    playUrlCache.getAll().associateBy { it.key }
                } catch (_: Exception) {
                    emptyMap()
                }
                val nowMs = System.currentTimeMillis()
                added = spliceTransport(plan, urlMap, urlCache, imageFormat, nowMs)
            }
            // 队列真相唯一在 QueueManager（同步经 listener）；added 按新增 key 数报数，保持旧语义
            queueManager.applyRefresh(plan.final, plan.playingFinalIdx, title)
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
    private suspend fun spliceTransport(
        plan: RefreshPlan,
        urlMap: Map<Track, String>,
        urlCache: Map<String, PlayUrlCacheEntity>,
        imageFormat: ImageFormat,
        nowMs: Long,
    ): Int {
        val expected = plan.base.toMutableList()
        var playIdx = plan.playingTransportIdx
        var added = 0
        for ((i, item) in plan.final.withIndex()) {
            val m = expected.getOrNull(i)
            if (m != null && m.queueKey() == item.queueKey()) {
                if (m != item && i != playIdx) {
                    val url = urlCache["${item.bvid}:${item.cid}"]
                        ?.takeIf { it.isFresh(nowMs) }?.url
                    if (url != null) {
                        try {
                            transport.replaceMediaItem(i, playUrlPipeline.buildItem(item, url, imageFormat))
                        } catch (_: Exception) {
                        }
                    }
                }
                continue
            }
            if (expected.any { it.queueKey() == item.queueKey() }) break // 重排：停手保平安
            val url = urlMap[item] ?: continue // 新增解析失败：UI 和 transport 都跳过该集
            try {
                transport.addMediaItem(i, playUrlPipeline.buildItem(item, url, imageFormat))
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
     * Play queue starting at [index]. Builds MediaItems via PlayUrlResolver (audio-only),
     * calls transport.setMediaItems, prepare, play. Launches history save throttle 1s.
     * 加载期间 isLoading=true 且互斥，防止重复点击。
     */
    suspend fun play(
        tracks: List<Track>,
        index: Int,
        sourceTitle: String? = null,
        startPositionMs: Long? = null,
    ) {
        if (tracks.isEmpty()) return
        // 防重入：正在加载时忽略新的播放请求
        if (!playMutex.tryLock()) return
        try {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            // 先拉起前台服务：后台保活 + 通知栏的前提；幂等，重复调用无副作用。
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, TingBiliPlaybackService::class.java)
            )
            val safeIndex = index.coerceIn(0, tracks.lastIndex)

            // 乐观更新：URL 逐个解析很慢，先让播放页/mini-player 立刻显示队列归属。
            _state.update {
                it.copy(
                    queue = tracks,
                    currentIndex = safeIndex,
                    currentTrack = tracks.getOrNull(safeIndex),
                    sourceTitle = sourceTitle?.takeIf { title -> title.isNotBlank() },
                    isPlaying = false,
                )
            }

            // 搜索结果不带 cid（cid=0），先用 view() 解析出真实分P
            val resolved = tracks.map { t -> if (t.cid == 0L) playUrlPipeline.resolveCid(t) else t }

            // playurl 批量解析（缓存命中即用、未命中并发补，见 resolveUrls）；失败项跳过。
            // 冷启动/重装恢复时缓存命中则 0 网络解析、秒开。
            val urlMap = playUrlPipeline.resolveUrls(resolved)
            val imageFormat = prefs.imageFormat.first()
            val items = resolved.mapNotNull { track ->
                urlMap[track]?.let { track to it }
            }
            if (items.isEmpty()) {
                _state.update { it.copy(isPlaying = false, isLoading = false, errorMessage = "暂无可播放音源") }
                return
            }

            // 目标曲目若不可播，取其后第一个可播项，否则第一个可播项
            val startIndex = items.indexOfFirst { it.first == resolved[safeIndex] }
                .takeIf { it >= 0 }
                ?: items.indexOfFirst { it.first == resolved.getOrNull(safeIndex) }
                    .takeIf { it >= 0 }
                ?: 0
            val playableTracks = items.map { it.first }
            val resolvedStartPosition = startPositionMs
                ?.coerceAtLeast(0L)
                ?.coerceAtMost(playableTracks[startIndex].durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
                ?: 0L

            // 持久化队列与起点，供下次冷启动恢复（Room 单行原子快照 + DataStore 进度）
            val resolvedSourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
            queueManager.setActiveQueue(playableTracks, startIndex, resolvedSourceTitle)
            persistPosition(resolvedStartPosition)
            transport.setMediaItems(
                items.map { (track, url) -> playUrlPipeline.buildItem(track, url, imageFormat) },
                startIndex,
                resolvedStartPosition,
            )
            transport.prepare()
            transport.play()

            // Restore preferences: speed, repeatMode
            val speed = prefs.speed.first()
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
                // Resume track at saved history if exists
                val startTrack = playableTracks[startIndex]
                val saved = historyDao.get(startTrack.bvid, startTrack.cid)
                if (saved != null && saved.positionMs > 0L) {
                    transport.seekTo(saved.positionMs)
                    _state.update { it.copy(positionMs = saved.positionMs) }
                    persistPosition(saved.positionMs)
                }
            }

            historyTracker.start(clock())
            attachPlayerListener()
            urlRefreshRetry = 0
            scheduleProactiveRefresh()
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "播放失败") }
        } finally {
            // 确保加载态在异常或正常路径都收敛；若 play 已将 isLoading 置为 false 则保持
            if (_state.value.isLoading) {
                _state.update { it.copy(isLoading = false) }
            }
            if (playMutex.isLocked) playMutex.unlock()
        }
    }


    fun pause() {
        // 先对表再停：内存 position 最多滞后一个 poll 周期，直接落盘会丢半秒
        if (transport.isPlaying) {
            _state.update { it.copy(positionMs = transport.currentPosition) }
        }
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

    private fun currentKey(): Pair<String, Long>? =
        _state.value.currentTrack?.let { it.bvid to it.cid }

    fun resume() {
        // 若队列已恢复但尚未 prepare（冷启动后），走 play() 重建 MediaItem 并 seek 到保存进度
        if (queue.isNotEmpty() && (transport.mediaItemCount == 0 || transport.currentMediaItemIndex !in queue.indices)) {
            val idx = currentIndex.coerceIn(0, queue.lastIndex)
            val pos = _state.value.positionMs
            val sourceTitle = _state.value.sourceTitle
            effectiveScope().launch {
                play(queue, idx, sourceTitle, pos)
            }
            return
        }
        transport.play()
        _state.update { it.copy(isPlaying = true) }
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
        persistPosition(target)
    }

    /**
     * offload 开启时 DSP 管线不支持变速: 非 1.0x 请求直接拒绝并提示, 不断播.
     * 开关打开瞬间若正处变速, 由 AudioPipelinePolicy 一次性回 1.0x.
     */
    /**
     * offload 开启时 DSP 管线不支持变速: 非 1.0x 请求直接拒绝并提示, 不断播.
     * 开关打开瞬间若正处变速, 由 AudioPipelinePolicy 一次性回 1.0x.
     */
    suspend fun setSpeed(speed: Float) {
        when (val r = audioPolicy.requestSpeed(speed)) {
            is AudioPipelinePolicy.SpeedResult.Applied ->
                _state.update { it.copy(speed = r.speed, errorMessage = null) }
            AudioPipelinePolicy.SpeedResult.BlockedByOffload ->
                _state.update { it.copy(errorMessage = "DSP 省电开启时不支持倍速，已保持 1.0x") }
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
        val nextIdx = (currentIndex + 1) % queue.size
        // fire and forget — callers in compose scope
        effectiveScope().launch { play(queue, nextIdx) }
    }

    fun previous() {
        if (queue.isEmpty()) return
        val prevIdx = if (currentIndex - 1 < 0) queue.lastIndex else currentIndex - 1
        effectiveScope().launch { play(queue, prevIdx) }
    }

    fun release() {
        historyTracker.release()
        urlRefreshJob?.cancel(); urlRefreshJob = null
        sleepTimer.release()
        audioPolicy.release()
    }

    private var urlRefreshRetry = 0
    private var urlRefreshJob: Job? = null
    private companion object {
        const val MAX_URL_REFRESH_RETRY = 2
        const val PROACTIVE_REFRESH_MS = 90 * 60 * 1000L // 90min，B站 URL 约 2h 过期，提前刷新
    }

    private fun scheduleProactiveRefresh() {
        urlRefreshJob?.cancel()
        urlRefreshJob = effectiveScope().launch {
            delay(PROACTIVE_REFRESH_MS)
            val track = _state.value.currentTrack ?: return@launch
            if (!transport.isPlaying) return@launch
            val pos = transport.currentPosition
            // 解析失败（null/空）沿用旧 URI：下次错误时再重试
            val newUrl = playUrlPipeline.refreshUrl(track)
            if (!newUrl.isNullOrBlank() && currentIndex in queue.indices) {
                        val newItem = playUrlPipeline.buildItem(track, newUrl, prefs.imageFormat.first())
                        transport.replaceMediaItem(currentIndex, newItem)
                    }
        }
    }

    private var listenerAttached = false
    private fun attachPlayerListener() {
        if (listenerAttached) return
        listenerAttached = true
        transport.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = transport.currentMediaItemIndex
                if (idx in queue.indices) {
                    queueManager.moveCurrentTo(idx)
                    _state.update {
                        it.copy(
                            currentIndex = idx,
                            currentTrack = queue[idx],
                            positionMs = transport.currentPosition,
                            bufferedPositionMs = transport.bufferedPosition,
                            durationMs = queue[idx].durationMs,
                        )
                    }
                    // 切歌即时刷一次历史：节流 10s 下，播完一切就落盘，不用等下一 tick
                    effectiveScope().launch { historyTracker.flush() }
                    urlRefreshRetry = 0
                    scheduleProactiveRefresh()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying, isLoading = false) }
                if (isPlaying) urlRefreshRetry = 0
            }

            override fun onPlaybackStateChanged(state: Int) {
                // keep isPlaying in sync + 驱动加载态：buffering 时显示加载，ready/idle/ended 时收敛
                val buffering = state == Player.STATE_BUFFERING
                _state.update {
                    it.copy(
                        isPlaying = transport.isPlaying,
                        isLoading = buffering,
                        bufferedPositionMs = transport.bufferedPosition,
                    )
                }
                if (state == Player.STATE_READY) urlRefreshRetry = 0
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val isRecoverable = playUrlPipeline.isRecoverable(error)
                if (!isRecoverable || urlRefreshRetry >= MAX_URL_REFRESH_RETRY) {
                    _state.update { it.copy(isLoading = false, errorMessage = error.message ?: "播放出错") }
                    return
                }
                urlRefreshRetry++
                _state.update { it.copy(isLoading = true, errorMessage = null) }
                effectiveScope().launch {
                    delay(1000L * urlRefreshRetry)
                    val track = _state.value.currentTrack ?: run {
                        _state.update { it.copy(isLoading = false, errorMessage = "音源失效") }
                        return@launch
                    }
                    val pos = _state.value.positionMs.coerceAtLeast(transport.currentPosition)
                    // 解析失败（null/空）与旧 Result.Error 同义：报刷新失败，下次错误再重试
                    val newUrl = playUrlPipeline.refreshUrl(track)
                    if (newUrl.isNullOrBlank()) {
                                _state.update { it.copy(isLoading = false, errorMessage = "音源刷新失败") }
                                return@launch
                            }
                            val imageFormat = prefs.imageFormat.first()
                            val newItem = playUrlPipeline.buildItem(track, newUrl, imageFormat)
                            val idx = currentIndex
                            if (idx in queue.indices) {
                                transport.replaceMediaItem(idx, newItem)
                                transport.prepare()
                                transport.seekTo(pos)
                                transport.play()
                                _state.update { it.copy(isLoading = true) }
                                scheduleProactiveRefresh()
                            } else {
                                _state.update { it.copy(isLoading = false, errorMessage = "刷新失败") }
                            }
                        }
            }
        })
    }
}
