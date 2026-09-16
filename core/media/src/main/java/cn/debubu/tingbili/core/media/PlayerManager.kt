package cn.debubu.tingbili.core.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.biliImage
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
 * Audio-only: builds MediaItems from BiliRepository.getPlayUrl, sets ExoPlayer.
 * History throttle 1s — periodic save while playing.
 * Step seek respects PreferencesRepository.stepSec.
 * 传输层为 PlayerHandle（生产经 MediaController），ExoPlayer 唯一释放权在 Service。
 */
@Singleton
class PlayerManager @Inject constructor(
    private val transport: PlayerHandle,
    private val historyDao: HistoryDao,
    private val prefs: PreferencesRepository,
    private val biliRepository: BiliRepository,
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

    private var historyJob: Job? = null
    private var positionPollJob: Job? = null
    private val playMutex = Mutex()

    init {
        // 冷启动静默恢复上次队列与进度（暂停态，仅恢复 UI 状态，不自动播放）
        // 使用 scope 而非 effectiveScope：构造时 testScope 尚未来得及注入，测试可显式调用 restoreLastPlayback()
        scope.launch { restoreLastPlayback() }
    }

    /** 恢复上次播放的队列与进度到 UI 状态，暂停态；损坏/空队列时静默忽略 */
    suspend fun restoreLastPlayback() {
        try {
            val savedQueue = prefs.lastQueue.first()
            if (savedQueue.isEmpty()) return
            val savedIndex = prefs.lastIndex.first().coerceIn(0, savedQueue.lastIndex)
            val savedPos = prefs.lastPositionMs.first().coerceAtLeast(0L)
            val savedSourceTitle = prefs.lastSourceTitle.first()
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

    private fun persistQueue() {
        val q = queue
        val idx = currentIndex
        val pos = _state.value.positionMs
        if (q.isEmpty() || idx !in q.indices) return
        effectiveScope().launch { prefs.setLastPlayback(q, idx, pos, _state.value.sourceTitle) }
    }

    private fun persistPosition(pos: Long) {
        if (queue.isEmpty()) return
        effectiveScope().launch { prefs.setLastPosition(pos.coerceAtLeast(0L)) }
    }

    /**
     * Play queue starting at [index]. Builds MediaItems via BiliRepository.getPlayUrl (audio-only),
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

            // Build MediaItems — resolve playUrl per track (audio-only); 跳过解析失败的项
            val items = resolved.mapNotNull { track ->
                val url = when (val r = biliRepository.getPlayUrl(track.bvid, track.cid)) {
                    is Result.Success -> r.data
                    is Result.Error -> null
                }
                track.takeIf { !url.isNullOrBlank() }?.let { it to url }
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

            // 持久化队列与起点进度，供下次冷启动恢复
            val resolvedSourceTitle = sourceTitle?.takeIf { it.isNotBlank() }
            effectiveScope().launch {
                prefs.setLastPlayback(playableTracks, startIndex, resolvedStartPosition, resolvedSourceTitle)
            }
            val imageFormat = prefs.imageFormat.first()

            transport.setMediaItems(
                items.map { (track, url) ->
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaId("${track.bvid}:${track.cid}")
                        .setCustomCacheKey("${track.bvid}:${track.cid}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(track.author)
                                .setArtworkUri(track.cover.takeIf { it.isNotBlank() }?.biliImage(480, 480, imageFormat)?.let(android.net.Uri::parse))
                                .build()
                        )
                        .build()
                },
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

    /** cid=0（搜索结果）时经 view() 解析第一个分P 的真实 cid */
    private suspend fun resolveCid(track: Track): Track =
        when (val v = biliRepository.getView(track.bvid)) {
            is Result.Success -> v.data.firstOrNull()?.let { first ->
                track.copy(
                    cid = first.cid,
                    durationMs = first.durationMs.takeIf { it > 0L } ?: track.durationMs,
                )
            } ?: track
            is Result.Error -> track
        }

    fun pause() {
        transport.pause()
        _state.update { it.copy(isPlaying = false) }
        persistPosition(_state.value.positionMs)
    }

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

    private fun startHistoryThrottle() {
        historyJob?.cancel()
        historyJob = effectiveScope().launch {
            while (true) {
                delay(1000L)
                val track = _state.value.currentTrack ?: continue
                if (!transport.isPlaying) continue
                val pos = transport.currentPosition
                historyDao.save(
                    HistoryEntity(
                        bvid = track.bvid,
                        cid = track.cid,
                        positionMs = pos,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                // 同步更新 DataStore 里的 lastPosition，供 UI 恢复进度环使用
                prefs.setLastPosition(pos)
            }
        }
    }

    private fun startPositionPoll() {
        positionPollJob?.cancel()
        positionPollJob = effectiveScope().launch {
            while (true) {
                delay(500L)
                if (transport.isPlaying) {
                    _state.update {
                        it.copy(
                            positionMs = transport.currentPosition,
                            bufferedPositionMs = transport.bufferedPosition,
                            isPlaying = true,
                        )
                    }
                    // 轻量持久化进度，避免仅依赖 history 1s 节流
                    prefs.setLastPosition(transport.currentPosition)
                }
            }
        }
    }

    fun release() {
        historyJob?.cancel(); historyJob = null
        positionPollJob?.cancel(); positionPollJob = null
        urlRefreshJob?.cancel(); urlRefreshJob = null
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
            when (val r = biliRepository.getPlayUrl(track.bvid, track.cid)) {
                is Result.Success -> {
                    val newUrl = r.data
                    if (!newUrl.isNullOrBlank() && currentIndex in queue.indices) {
                        val imageFormat = prefs.imageFormat.first()
                        val newItem = MediaItem.Builder()
                            .setUri(newUrl)
                            .setMediaId("${track.bvid}:${track.cid}")
                            .setCustomCacheKey("${track.bvid}:${track.cid}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.author)
                                    .setArtworkUri(track.cover.takeIf { it.isNotBlank() }?.biliImage(480, 480, imageFormat)?.let(android.net.Uri::parse))
                                    .build()
                            ).build()
                        transport.replaceMediaItem(currentIndex, newItem)
                    }
                }
                is Result.Error -> { /* 下次错误时再重试 */ }
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
                    when (val r = biliRepository.getPlayUrl(track.bvid, track.cid)) {
                        is Result.Success -> {
                            val newUrl = r.data
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
                                        .setArtworkUri(track.cover.takeIf { it.isNotBlank() }?.biliImage(480, 480, imageFormat)?.let(android.net.Uri::parse))
                                        .build()
                                ).build()
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
                        is Result.Error -> {
                            _state.update { it.copy(isLoading = false, errorMessage = r.msg) }
                        }
                    }
                }
            }
        })
    }
}
