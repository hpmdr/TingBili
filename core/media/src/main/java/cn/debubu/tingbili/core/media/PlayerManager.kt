package cn.debubu.tingbili.core.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlayQueueDao
import cn.debubu.tingbili.core.data.db.PlayQueueJson
import cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity
import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    private var queue: List<Track> = emptyList()
    private var currentIndex: Int = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Test hook — inject TestScope to control virtual time for throttle tests. */
    internal var testScope: CoroutineScope? = null
    private fun effectiveScope(): CoroutineScope = testScope ?: scope
    /** Test hook — clock for sleep deadline; production=wall clock, tests inject virtual time. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private var historyJob: Job? = null
    private var positionPollJob: Job? = null

    // 本地累计收听时长统计：当前条目累计值 + 上次 tick 位置（排除 seek 跳变）
    private var listenAccumMs = 0L
    private var lastTickPos = -1L
    private var lastListenKey: Pair<String, Long>? = null
    private val playMutex = Mutex()

    init {
        // 冷启动静默恢复上次队列与进度（暂停态，仅恢复 UI 状态，不自动播放）
        // 使用 scope 而非 effectiveScope：构造时 testScope 尚未来得及注入，测试可显式调用 restoreLastPlayback()
        scope.launch {
            restoreLastPlayback()
            restoreSleepTimer()
        }
        // 硬解开关实时镜像：解码器每次建实例都查最新值，切换后下一首生效，无需重建播放器
        scope.launch {
            prefs.audioHwDecode.collect { PreferHardwareAudioDecoderSelector.preferHardware = it }
        }
        // DSP offload 开关实时镜像：经传输层下发 ExoPlayer，切换后下一首生效
        scope.launch {
            prefs.audioOffload.collect { transport.setAudioOffloadEnabled(it) }
        }
        // DataStore playurl 大 Map 一次性搬进 Room（读完即删键，之后不再写入旧位置）
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
        try {
            // Room 单行快照优先；空表时搬一次 DataStore 旧三键（搬完即删键），之后只走 Room
            var snapshot = try {
                playQueue.get()
            } catch (_: Exception) {
                null
            }
            if (snapshot == null) {
                val (legacyQueue, legacyIndex, legacyTitle) = try {
                    prefs.drainLastQueue()
                } catch (_: Exception) {
                    Triple(emptyList(), -1, null)
                }
                if (legacyQueue.isNotEmpty()) {
                    snapshot = PlayQueueSnapshotEntity(
                        tracksJson = PlayQueueJson.encode(legacyQueue.take(100)),
                        currentIndex = legacyIndex.coerceIn(0, legacyQueue.lastIndex),
                        sourceTitle = legacyTitle?.takeIf { it.isNotBlank() },
                        updatedAt = System.currentTimeMillis(),
                    )
                    try {
                        playQueue.save(snapshot)
                    } catch (_: Exception) {
                    }
                }
            }
            val savedQueue = snapshot?.let { PlayQueueJson.decode(it.tracksJson) }.orEmpty()
            if (savedQueue.isEmpty()) return
            val savedIndex = (snapshot?.currentIndex ?: -1).coerceIn(0, savedQueue.lastIndex)
            val savedPos = prefs.lastPositionMs.first().coerceAtLeast(0L)
            val savedSourceTitle = snapshot?.sourceTitle
            val track = savedQueue.getOrNull(savedIndex) ?: return
            queue = savedQueue
            currentIndex = savedIndex
            val speed = prefs.speed.first()
            val repeat = prefs.repeatMode.first()
            val clampedPos = savedPos.coerceIn(0L, track.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
            _state.update {
                it.copy(
                    queue = savedQueue,
                    currentIndex = savedIndex,
                    currentTrack = track,
                    sourceTitle = savedSourceTitle,
                    positionMs = clampedPos,
                    durationMs = track.durationMs,
                    isPlaying = false,
                    speed = speed,
                    repeatMode = repeat
                )
            }
        } catch (_: Exception) {
            // 损坏数据忽略，保持空状态
        }
    }

    private suspend fun saveQueueSnapshot(tracks: List<Track>, index: Int, sourceTitle: String?) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        val capped = if (tracks.size > MAX_QUEUE_SNAPSHOT) tracks.take(MAX_QUEUE_SNAPSHOT) else tracks
        val snapshot = PlayQueueSnapshotEntity(
            tracksJson = PlayQueueJson.encode(capped),
            currentIndex = index.coerceIn(0, capped.lastIndex),
            sourceTitle = sourceTitle?.takeIf { it.isNotBlank() },
            updatedAt = System.currentTimeMillis(),
        )
        // 单行 Upsert 原子落盘：队列/下标/合集名要么一起新，要么一起旧
        try {
            playQueue.save(snapshot)
        } catch (_: Exception) {
        }
    }

    private fun persistQueue() {
        val q = queue
        val idx = currentIndex
        if (q.isEmpty() || idx !in q.indices) return
        effectiveScope().launch { saveQueueSnapshot(q, idx, _state.value.sourceTitle) }
    }

    private fun persistPosition(pos: Long) {
        if (queue.isEmpty()) return
        effectiveScope().launch { prefs.setLastPosition(pos.coerceAtLeast(0L)) }
    }

    /**
     * playurl 批量解析：持久缓存命中即用，未命中并发补（限 4 并发防风控），结果回写。
     * 返回 track→url（解析失败的项直接丢掉，调用方跳过）。
     */
    private suspend fun resolveUrls(tracks: List<Track>): Map<Track, String> {
        if (tracks.isEmpty()) return emptyMap()
        val nowMs = System.currentTimeMillis()
        val urlCache = try {
            playUrlCache.getAll().associateBy { it.key }
        } catch (_: Exception) {
            emptyMap()
        }
        val out = mutableMapOf<Track, String>()
        val missing = mutableListOf<Track>()
        for (t in tracks) {
            val hit = urlCache["${t.bvid}:${t.cid}"]?.takeIf { it.isFresh(nowMs) }?.url
            if (hit.isNullOrBlank()) missing.add(t) else out[t] = hit
        }
        if (missing.isNotEmpty()) {
            val semaphore = Semaphore(4)
            val fetched = supervisorScope {
                missing.map { track ->
                    async {
                        runCatching {
                            semaphore.withPermit {
                                val url = playUrls.resolve(track.bvid, track.cid)?.takeIf { it.isNotBlank() }
                                if (url != null) track to url else null
                            }
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            if (fetched.isNotEmpty()) {
                playUrlCache.putAll(
                    fetched.map { (t, u) -> PlayUrlCacheEntity("${t.bvid}:${t.cid}", u, nowMs) }
                )
                playUrlCache.pruneExpired(nowMs - PlayUrlCacheEntity.TTL_MS)
                playUrlCache.pruneOverflow(PlayUrlCacheEntity.MAX_ENTRIES)
                fetched.forEach { (t, u) -> out[t] = u }
            }
        }
        return out
    }

    /** Track→MediaItem（mediaId `bvid:cid`：播放/刷新对位、测试断言都认它） */
    private fun Track.toMediaItem(url: String, imageFormat: ImageFormat): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId("$bvid:$cid")
            .setCustomCacheKey("$bvid:$cid")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(author)
                    .setArtworkUri(artwork.artwork(cover, imageFormat))
                    .build()
            )
            .build()

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
                queue = newTracks
                currentIndex = -1
                _state.update { it.copy(queue = newTracks, currentIndex = -1, currentTrack = null, sourceTitle = title) }
                return QueueRefreshResult(0, 0, 0)
            }
            val plan = computeRefreshPlan(old, currentIndex, newTracks)
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
                val urlMap = resolveUrls(plan.addedTracks)
                val imageFormat = prefs.imageFormat.first()
                val urlCache = try {
                    playUrlCache.getAll().associateBy { it.key }
                } catch (_: Exception) {
                    emptyMap()
                }
                val nowMs = System.currentTimeMillis()
                added = spliceTransport(plan, urlMap, urlCache, imageFormat, nowMs)
            }
            // transport 对不上时只更新 UI（added 按新增 key 数报数，保持旧语义）
            queue = plan.final
            currentIndex = plan.playingFinalIdx
            _state.update {
                it.copy(
                    queue = plan.final,
                    currentIndex = plan.playingFinalIdx,
                    currentTrack = plan.final.getOrNull(plan.playingFinalIdx),
                    sourceTitle = title,
                )
            }
            persistQueue()
            return QueueRefreshResult(
                if (transportOk) added else plan.addedIfNoTransport,
                plan.removed,
                plan.updated,
            )
        }

    private fun Track.key() = bvid to cid

    /**
     * 刷新计划的纯计算：不碰 transport/IO。输入旧队列 + 在播下标 + 新列表，
     * 算出最终队列/在播落点/增删改计数/待删旧下标/删后 transport 期望顺序。
     * 在播被删则原位保留（拍板语义），播完再消失。
     */
    private fun computeRefreshPlan(
        old: List<Track>,
        currentIndex: Int,
        newTracks: List<Track>,
    ): RefreshPlan {
        val newKeys = newTracks.map { it.key() }.toSet()
        val oldKeys = old.map { it.key() }.toSet()
        val playing = old[currentIndex]
        // 最终队列：新顺序；在播被删则在原下标处保留
        val final = newTracks.toMutableList()
        if (playing.key() !in newKeys) final.add(currentIndex.coerceIn(0, final.size), playing)
        val removed = old.count { it.key() !in newKeys && it.key() != playing.key() }
        val updated = final.count { f -> old.find { it.key() == f.key() }?.let { it != f } == true }
        // 待删旧下标（倒序，不含在播项）；删完后 transport 顺序 == base
        val deletions = old.indices.sortedDescending()
            .filter { i -> i != currentIndex && old[i].key() !in newKeys }
        val base = old.filterIndexed { i, t -> i == currentIndex || t.key() in newKeys }
        // 新增项（base 里没有的）：调用方批量解析 playurl
        val addedTracks = final.filter { f -> base.none { it.key() == f.key() } }
        val addedIfNoTransport = final.count { it.key() !in oldKeys }
        val playingFinalIdx = final.indexOfFirst { it.key() == playing.key() }
        return RefreshPlan(
            final = final,
            playingFinalIdx = playingFinalIdx,
            removed = removed,
            updated = updated,
            deletions = deletions,
            base = base,
            addedTracks = addedTracks,
            addedIfNoTransport = addedIfNoTransport,
            playingTransportIdx = currentIndex - deletions.count { it < currentIndex },
        )
    }

    private data class RefreshPlan(
        val final: List<Track>,
        /** 在播项在 final 中的下标（恒 >= 0：在播被删也会被加回） */
        val playingFinalIdx: Int,
        val removed: Int,
        val updated: Int,
        val deletions: List<Int>,
        /** 删完后 transport 的期望顺序（immutable，splice 内不再边走边改语义） */
        val base: List<Track>,
        val addedTracks: List<Track>,
        /** transport 对不上时的 added 口径（新增 key 数，与旧 fallback 分支一致） */
        val addedIfNoTransport: Int,
        /** 在播项在删后 transport 中的下标（删到之前的项要同步挪） */
        val playingTransportIdx: Int,
    )

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
            if (m != null && m.key() == item.key()) {
                if (m != item && i != playIdx) {
                    val url = urlCache["${item.bvid}:${item.cid}"]
                        ?.takeIf { it.isFresh(nowMs) }?.url
                    if (url != null) {
                        try {
                            transport.replaceMediaItem(i, item.toMediaItem(url, imageFormat))
                        } catch (_: Exception) {
                        }
                    }
                }
                continue
            }
            if (expected.any { it.key() == item.key() }) break // 重排：停手保平安
            val url = urlMap[item] ?: continue // 新增解析失败：UI 和 transport 都跳过该集
            try {
                transport.addMediaItem(i, item.toMediaItem(url, imageFormat))
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
            val resolved = tracks.map { t -> if (t.cid == 0L) resolveCid(t) else t }

            // playurl 批量解析（缓存命中即用、未命中并发补，见 resolveUrls）；失败项跳过。
            // 冷启动/重装恢复时缓存命中则 0 网络解析、秒开。
            val urlMap = resolveUrls(resolved)
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
            queue = playableTracks
            currentIndex = startIndex
            val resolvedStartPosition = startPositionMs
                ?.coerceAtLeast(0L)
                ?.coerceAtMost(playableTracks[startIndex].durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
                ?: 0L

            // 持久化队列与起点，供下次冷启动恢复（Room 单行原子快照 + DataStore 进度）
            val resolvedSourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
            effectiveScope().launch { saveQueueSnapshot(playableTracks, startIndex, resolvedSourceTitle) }
            persistPosition(resolvedStartPosition)
            transport.setMediaItems(
                items.map { (track, url) -> track.toMediaItem(url, imageFormat) },
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

            startHistoryThrottle()
            startPositionPoll()
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

    /** cid=0（搜索结果）时经目录解析第一个分P 的真实 cid */
    private suspend fun resolveCid(track: Track): Track =
        catalog.tracksOf(track.bvid).firstOrNull()?.let { first ->
            track.copy(
                cid = first.cid,
                durationMs = first.durationMs.takeIf { it > 0L } ?: track.durationMs,
            )
        } ?: track

    fun pause() {
        // 先对表再停：内存 position 最多滞后一个 poll 周期，直接落盘会丢半秒
        if (transport.isPlaying) {
            _state.update { it.copy(positionMs = transport.currentPosition) }
        }
        transport.pause()
        _state.update { it.copy(isPlaying = false) }
        persistPosition(_state.value.positionMs)
        // 暂停即时刷一次历史：节流 10s 下，退出/切后台不再丢最后一段
        effectiveScope().launch { saveHistorySnapshot(force = true) }
    }

    // ── 睡眠定时（绝对 deadline + DataStore 落盘；杀进程/Doze 冻结后可恢复校准；触发=pause，自带落盘进度） ──
    private var sleepJob: Job? = null

    /**
     * 倒计时定时：seconds 秒后停止，60 秒起（恢复路径可小于 60，不钳制）。
     * 按绝对 deadline 走，每秒用 clock() 重算剩余，Doze 冻结 delay 后能一次跳变追齐。
     * @param finishChapter 到点后是否播完本章再停（到点后转入播完即停阶段，不立即暂停）。
     */
    fun startSleepTimer(seconds: Int, finishChapter: Boolean = false) {
        sleepJob?.cancel()
        val total = seconds.coerceAtLeast(60)
        val deadline = clock() + total * 1000L
        _state.update {
            it.copy(
                sleepRemainSec = total,
                sleepTotalSec = total,
                sleepFinishChapter = finishChapter,
                sleepEndOfChapter = false,
            )
        }
        effectiveScope().launch {
            try {
                prefs.saveSleepTimerState(deadline, total, finishChapter)
            } catch (_: Exception) {
            }
        }
        sleepJob = effectiveScope().launch {
            launchSleepCountdown(deadline)
        }
    }

    /** 冷启动/进程重建后恢复定时：未来→继续倒计时；已过期→直接停或转尾；无落盘→忽略 */
    suspend fun restoreSleepTimer() {
        try {
            val tail = try {
                prefs.sleepTail.first()
            } catch (_: Exception) {
                false
            }
            if (tail) {
                val finish = try {
                    prefs.sleepFinishChapter.first()
                } catch (_: Exception) {
                    true
                }
                if (currentKey() == null) {
                    try {
                        prefs.clearSleepTimerState()
                    } catch (_: Exception) {
                    }
                    return
                }
                _state.update {
                    it.copy(
                        sleepRemainSec = null,
                        sleepTotalSec = null,
                        sleepFinishChapter = finish,
                        sleepEndOfChapter = true,
                    )
                }
                sleepJob?.cancel()
                sleepJob = effectiveScope().launch { watchChapterTail(currentKey()!!) }
                return
            }
            val deadline = try {
                prefs.sleepDeadlineMs.first()
            } catch (_: Exception) {
                0L
            }
            if (deadline <= 0L) return
            val total = try {
                prefs.sleepTotalSec.first()
            } catch (_: Exception) {
                0
            }
            val finish = try {
                prefs.sleepFinishChapter.first()
            } catch (_: Exception) {
                false
            }
            val remain = ((deadline - clock()) / 1000).toInt()
            if (remain <= 0) {
                if (finish) enterChapterTail() else fireSleepTimer()
                return
            }
            _state.update {
                it.copy(
                    sleepRemainSec = remain,
                    sleepTotalSec = total.coerceAtLeast(remain),
                    sleepFinishChapter = finish,
                    sleepEndOfChapter = false,
                )
            }
            sleepJob?.cancel()
            sleepJob = effectiveScope().launch {
                launchSleepCountdown(deadline)
            }
        } catch (_: Exception) {
        }
    }

    /** 倒计时主循环：按 deadline 重算剩余，抗 delay 被系统拉长 */
    private suspend fun launchSleepCountdown(deadlineMs: Long) {
        while (true) {
            delay(1000)
            val remain = ((deadlineMs - clock()) / 1000).toInt()
            if (remain <= 0) break
            _state.update { it.copy(sleepRemainSec = remain) }
        }
        if (_state.value.sleepFinishChapter) enterChapterTail() else fireSleepTimer()
    }

    /**
     * 倒计时进行中改“到点后播完本章再停”开关。播完即停阶段关掉=整单取消
     *（否则关了开关定时还会停在章尾，不符合直觉）。
     */
    fun setSleepFinishChapter(v: Boolean) {
        val s = _state.value
        if (s.sleepEndOfChapter) {
            if (!v) cancelSleepTimer()
            return
        }
        if (s.sleepRemainSec != null) {
            _state.update { it.copy(sleepFinishChapter = v) }
            effectiveScope().launch {
                try {
                    prefs.setSleepFinishChapter(v)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 倒计时到点 + 开关开：转入播完即停阶段。切歌或播到尾时暂停；手动暂停不取消（章还没完） */
    private fun enterChapterTail() {
        val key = currentKey()
        if (key == null) {
            fireSleepTimer()
            return
        }
        _state.update { it.copy(sleepRemainSec = null, sleepTotalSec = null, sleepEndOfChapter = true) }
        effectiveScope().launch {
            try {
                prefs.saveSleepTailState()
            } catch (_: Exception) {
            }
        }
        sleepJob?.cancel()
        sleepJob = effectiveScope().launch { watchChapterTail(key) }
    }

    private suspend fun watchChapterTail(key: Pair<String, Long>) {
        while (true) {
            delay(1000)
            val s = _state.value
            val chapterOver = currentKey() != key ||
                (s.durationMs > 0 && s.positionMs >= s.durationMs - CHAPTER_END_MARGIN_MS)
            if (chapterOver) {
                fireSleepTimer()
                return
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        effectiveScope().launch {
            try {
                prefs.clearSleepTimerState()
            } catch (_: Exception) {
            }
        }
        _state.update {
            it.copy(
                sleepRemainSec = null,
                sleepTotalSec = null,
                sleepFinishChapter = false,
                sleepEndOfChapter = false,
            )
        }
    }

    private fun fireSleepTimer() {
        // pause() 内已落盘进度，满足“定时停止必须记好进度”
        effectiveScope().launch {
            try {
                prefs.clearSleepTimerState()
            } catch (_: Exception) {
            }
        }
        pause()
        _state.update {
            it.copy(
                sleepRemainSec = null,
                sleepTotalSec = null,
                sleepFinishChapter = false,
                sleepEndOfChapter = false,
            )
        }
    }

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
        effectiveScope().launch { saveHistorySnapshot(force = true) }
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

    suspend fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        prefs.setSpeed(clamped)
        transport.setPlaybackSpeed(clamped)
        _state.update { it.copy(speed = clamped) }
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

    /**
     * 历史落盘快照：写 Room 全表订阅的源头。
     * - 常态节流 [HISTORY_SAVE_INTERVAL_MS]，播放器 1s 写是全 App 1Hz 抖动的根因；
     * - 切歌/seek/暂停走 [force] 即时写，保证退出/切后台不丢最后一秒。
     * force 写用内存态 position（seek 刚落点 transport 还没跟上），不参与累计收听。
     */
    private suspend fun saveHistorySnapshot(force: Boolean = false) {
        val track = _state.value.currentTrack ?: return
        if (!force && !transport.isPlaying) return
        val pos = if (force) _state.value.positionMs else transport.currentPosition
        // 换集后重载该条目已有的累计收听时长
        val key = track.bvid to track.cid
        if (key != lastListenKey) {
            listenAccumMs = historyDao.get(track.bvid, track.cid)?.listenDurationMs ?: 0L
            lastListenKey = key
            lastTickPos = pos
        } else if (!force) {
            // 累计收听：仅统计正常播放推进（排除 seek 跳变/换集），粒度约节流间隔
            val delta = pos - lastTickPos
            if (delta in 1..(HISTORY_SAVE_INTERVAL_MS + 2000L)) listenAccumMs += delta
            lastTickPos = pos
        }
        historyDao.save(
            HistoryEntity(
                bvid = track.bvid,
                cid = track.cid,
                positionMs = pos,
                updatedAt = System.currentTimeMillis(),
                listenDurationMs = listenAccumMs
            )
        )
        // DataStore 的 lastPosition 并入历史节流写：poll 只改内存，不再 500ms 写一次文件
        prefs.setLastPosition(pos)
    }

    private fun startHistoryThrottle() {
        historyJob?.cancel()
        historyJob = effectiveScope().launch {
            while (true) {
                delay(HISTORY_SAVE_INTERVAL_MS)
                saveHistorySnapshot()
            }
        }
    }

    private fun startPositionPoll() {
        positionPollJob?.cancel()
        positionPollJob = effectiveScope().launch {
            while (true) {
                delay(POSITION_POLL_INTERVAL_MS)
                if (transport.isPlaying) {
                    // 纯内存态更新：持久化并入历史节流写，这里不再碰 DataStore
                    _state.update {
                        it.copy(
                            positionMs = transport.currentPosition,
                            bufferedPositionMs = transport.bufferedPosition,
                            isPlaying = true,
                        )
                    }
                }
            }
        }
    }

    fun release() {
        historyJob?.cancel(); historyJob = null
        positionPollJob?.cancel(); positionPollJob = null
        urlRefreshJob?.cancel(); urlRefreshJob = null
        sleepJob?.cancel(); sleepJob = null
    }

    private var urlRefreshRetry = 0
    private var urlRefreshJob: Job? = null
    private companion object {
        /** 队列快照上限（与旧 DataStore 版一致，控单行 JSON 大小） */
        const val MAX_QUEUE_SNAPSHOT = 100
        /** 历史落盘节流：10s 写一次 Room，1s 写是全 App 1Hz 抖动的根因 */
        const val HISTORY_SAVE_INTERVAL_MS = 10_000L
        /** 进度轮询：纯内存态更新 UI，不碰磁盘 */
        const val POSITION_POLL_INTERVAL_MS = 500L
        /** 章尾判定余量：距结束不足此时长即视为播完 */
        const val CHAPTER_END_MARGIN_MS = 1500L
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
            val newUrl = playUrls.resolve(track.bvid, track.cid)
            if (!newUrl.isNullOrBlank() && currentIndex in queue.indices) {
                        effectiveScope().launch {
                            playUrlCache.putAll(listOf(PlayUrlCacheEntity("${track.bvid}:${track.cid}", newUrl, System.currentTimeMillis())))
                        }
                        val newItem = track.toMediaItem(newUrl, prefs.imageFormat.first())
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
                    currentIndex = idx
                    _state.update {
                        it.copy(
                            currentIndex = idx,
                            currentTrack = queue[idx],
                            positionMs = transport.currentPosition,
                            bufferedPositionMs = transport.bufferedPosition,
                            durationMs = queue[idx].durationMs,
                        )
                    }
                    persistQueue()
                    // 切歌即时刷一次历史：节流 10s 下，播完一切就落盘，不用等下一 tick
                    effectiveScope().launch { saveHistorySnapshot(force = true) }
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
                val isRecoverable = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    || error.errorCodeName.contains("403", ignoreCase = true)
                    || error.message?.contains("403") == true
                    || error.message?.contains("expire", ignoreCase = true) == true
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
                    val newUrl = playUrls.resolve(track.bvid, track.cid)
                    if (newUrl.isNullOrBlank()) {
                                _state.update { it.copy(isLoading = false, errorMessage = "音源刷新失败") }
                                return@launch
                            }
                            val imageFormat = prefs.imageFormat.first()
                            val newItem = MediaItem.Builder()
                                .setUri(newUrl)
                                .setMediaId("${track.bvid}:${track.cid}")
                                .setCustomCacheKey("${track.bvid}:${track.cid}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(track.title)
                                        .setArtist(track.author)
                                        .setArtworkUri(artwork.artwork(track.cover, imageFormat))
                                        .build()
                                ).build()
                            val idx = currentIndex
                            if (idx in queue.indices) {
                                effectiveScope().launch {
                                    playUrlCache.putAll(listOf(PlayUrlCacheEntity("${track.bvid}:${track.cid}", newUrl, System.currentTimeMillis())))
                                }
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
