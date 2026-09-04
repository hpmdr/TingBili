package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRepository
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
 */
@Singleton
class PlayerManager @Inject constructor(
    private val player: PlayerHandle,
    private val historyDao: HistoryDao,
    private val prefs: PreferencesRepository,
    private val biliRepository: BiliRepository,
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
     * calls player.setMediaItems, prepare, play. Launches history save throttle 1s.
     */
    suspend fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        val safeIndex = index.coerceIn(0, tracks.lastIndex)

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

        player.setMediaItems(
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
        player.prepare()
        player.play()

        // Restore preferences: speed, repeatMode
        val speed = prefs.speed.first()
        val repeat = prefs.repeatMode.first()
        if (speed != 1f) player.setPlaybackSpeed(speed)
        player.repeatMode = when (repeat) {
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
            player.seekTo(saved.positionMs)
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
        player.pause()
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        player.play()
        _state.update { it.copy(isPlaying = true) }
    }

    fun toggle() {
        if (player.isPlaying) pause() else resume()
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, player.duration.coerceAtLeast(0L).let { if (it == 0L) Long.MAX_VALUE else it }.coerceAtLeast(0L))
        // coerce to valid range — if duration unknown (0), just clamp at 0..MAX
        val clamped = target.coerceAtLeast(0L)
        player.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    /**
     * Step seek respects config [PreferencesRepository.stepSec].
     * dir: +1 forward, -1 backward.
     */
    suspend fun seekStep(dir: Int) {
        val stepSec = prefs.stepSec.first()
        val cur = player.currentPosition
        val dur = player.duration
        val max = if (dur > 0L && dur != androidx.media3.common.C.TIME_UNSET) dur else Long.MAX_VALUE
        val target = (cur + dir * stepSec * 1000L).coerceIn(0L, max)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    suspend fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        prefs.setSpeed(clamped)
        player.setPlaybackSpeed(clamped)
        _state.update { it.copy(speed = clamped) }
    }

    suspend fun setRepeatMode(mode: Int) {
        val clamped = mode.coerceIn(PlaybackState.REPEAT_MODE_OFF, PlaybackState.REPEAT_MODE_ALL)
        prefs.setRepeatMode(clamped)
        player.repeatMode = when (clamped) {
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
                if (!player.isPlaying) continue
                val pos = player.currentPosition
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
                if (player.isPlaying) {
                    _state.update { it.copy(positionMs = player.currentPosition, isPlaying = true) }
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
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                if (idx in queue.indices) {
                    currentIndex = idx
                    _state.update { it.copy(currentIndex = idx, currentTrack = queue[idx], positionMs = player.currentPosition, durationMs = queue[idx].durationMs) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                // keep isPlaying in sync
                _state.update { it.copy(isPlaying = player.isPlaying) }
            }
        })
    }
}
