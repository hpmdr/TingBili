package cn.debubu.tingbili.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.toLyricLines
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val repeatMode: Int = 0,
    val track: cn.debubu.tingbili.core.data.model.Track? = null,
    val queue: List<cn.debubu.tingbili.core.data.model.Track> = emptyList(),
    val currentIndex: Int = -1,
    val lyrics: List<LyricLine> = emptyList(),
    val currentLyricIndex: Int = -1
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerManager,
    private val repo: BiliRepository
) : ViewModel() {

    private val subtitleFlow: Flow<List<LyricLine>> = player.state
        .map { it.currentTrack }
        .distinctUntilChanged()
        .flatMapLatest { track ->
            if (track == null) flow { emit(emptyList()) }
            else flow {
                val result = repo.getSubtitle(track.bvid, track.cid)
                val lines = when (result) {
                    is Result.Success -> result.data
                    is Result.Error -> emptyList()
                }
                emit(lines)
            }
        }

    val uiState: StateFlow<PlayerUiState> = combine(player.state, subtitleFlow) { p, lyrics ->
        val idx = if (lyrics.isEmpty()) -1 else LyricState(lyrics).indexFor(p.positionMs)
        PlayerUiState(
            isPlaying = p.isPlaying,
            positionMs = p.positionMs,
            durationMs = p.durationMs,
            speed = p.speed,
            repeatMode = p.repeatMode,
            track = p.currentTrack,
            queue = p.queue,
            currentIndex = p.currentIndex,
            lyrics = lyrics,
            currentLyricIndex = idx
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    // ---- 播放控制（委托 PlayerManager）----

    fun toggle() = player.toggle()

    fun previous() = player.previous()

    fun next() = player.next()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** 点击歌词行跳转到该行时间 */
    fun seekToLyric(line: LyricLine) = player.seekTo(line.timeMs)

    /** 倍速循环切换：0.5 → 1.0 → 1.5 → 2.0 → 3.0 */
    fun cycleSpeed() {
        val steps = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
        val cur = uiState.value.speed
        val next = steps.firstOrNull { it > cur + 0.01f } ?: steps.first()
        viewModelScope.launch { player.setSpeed(next) }
    }
}
