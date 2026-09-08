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

    /**
     * Play queue starting at [index]. Builds MediaItems via BiliRepository.getPlayUrl (audio-only),
     * calls transport.setMediaItems, prepare, play. Launches history save throttle 1s.
     */
    suspend fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
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
            _state.update { it.copy(isPlaying = false) }
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

        transport.setMediaItems(
            items.map { (track, url) ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMediaId("${track.bvid}:${track.cid}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.author)
                            .setArtworkUri(track.cover.let { if (it.isBlank()) null else android.net.Uri.parse(it) })
                            .build()
                    )
                    .build()
            },
            startIndex,
            0L
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
                isPlaying = true,
                positionMs = 0L,
                durationMs = playableTracks[startIndex].durationMs,
                repeatMode = repeat,
                speed = speed,
            )
        }

        // Resume track at saved history if exists
        val startTrack = playableTracks[startIndex]
        val saved = historyDao.get(startTrack.bvid, startTrack.cid)
        if (saved != null && saved.positionMs > 0L) {
            transport.seekTo(saved.positionMs)
            _state.update { it.copy(positionMs = saved.positionMs) }
        }

        startHistoryThrottle()
        startPositionPoll()
        attachPlayerListener()
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
    }

    fun resume() {
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
            }
        }
    }

    private fun startPositionPoll() {
        positionPollJob?.cancel()
        positionPollJob = effectiveScope().launch {
            while (true) {
                delay(500L)
                if (transport.isPlaying) {
                    _state.update { it.copy(positionMs = transport.currentPosition, isPlaying = true) }
                }
            }
        }
    }

    fun release() {
        historyJob?.cancel(); historyJob = null
        positionPollJob?.cancel(); positionPollJob = null
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
                    _state.update { it.copy(currentIndex = idx, currentTrack = queue[idx], positionMs = transport.currentPosition, durationMs = queue[idx].durationMs) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // keep isPlaying in sync
                _state.update { it.copy(isPlaying = transport.isPlaying) }
            }
        })
    }
}
