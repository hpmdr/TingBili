package cn.debubu.tingbili.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.SubtitleCache
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val sourceTitle: String? = null,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val stepSec: Int = 15,
    val speed: Float = 1f,
    val repeatMode: Int = 0,
    val track: cn.debubu.tingbili.core.data.model.Track? = null,
    val queue: List<cn.debubu.tingbili.core.data.model.Track> = emptyList(),
    val currentIndex: Int = -1,
    val lyrics: List<LyricLine> = emptyList(),
    val currentLyricIndex: Int = -1,
    /** 当前字幕轨标注（AI 轨会写明），无字幕时为 null */
    val subtitleLabel: String? = null,
    val subtitleIsAi: Boolean = false,
    /** 可用字幕轨（已排序），单轨/无轨时切换入口隐藏 */
    val subtitleTracks: List<cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack> = emptyList(),
    val subtitleIndex: Int = 0,
    /** 字幕栏状态：加载中 / 就绪 / 无字幕 / 未登录 / 失败可重试 */
    val subtitleLoadState: SubtitleLoadState = SubtitleLoadState.Loading,
    /** 字幕字号缩放（0.85/1.0/1.15/1.3，全屏+迷你一起跟） */
    val subtitleScale: Float = 1f,
    /** 睡眠定时剩余秒数，null=关闭 */
    val sleepRemainSec: Int? = null,
    /** 倒计时总秒数（设定值，弹窗高亮用） */
    val sleepTotalSec: Int? = null,
    /** 倒计时进行中的“到点后播完本章再停”开关 */
    val sleepFinishChapter: Boolean = false,
    val sleepEndOfChapter: Boolean = false
)

/** 字幕栏五态：Loading 首帧即出（不挡标题），NoAuth 提示登录，Error 给重试 */
enum class SubtitleLoadState {
    Loading, Ready, Empty, NoAuth, Error
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerManager,
    private val subtitles: SubtitleCache,
    private val auth: AuthRepository,
    private val prefs: PreferencesRepository,
    private val repo: BiliRepository,
) : ViewModel() {

    private val trackFlow = player.state
        .map { it.currentTrack }
        .distinctUntilChanged()

    private val selectedSubtitleIndex = MutableStateFlow(0)

    private data class SubState(
        val tracks: List<cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack> = emptyList(),
        val index: Int = 0,
        val lines: List<LyricLine> = emptyList(),
        val label: String? = null,
        val isAi: Boolean = false,
        val status: SubtitleLoadState = SubtitleLoadState.Loading,
    )

    /** 未登录：B 站字幕拉不出来，直接提示登录，不发网络请求 */
    val isLoggedIn: StateFlow<Boolean> = auth.sessionFlow
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, auth.sessionFlow.value != null)

    /** 字幕失败重试计数：+1 即按当前曲目重走一遍 */
    private val _subtitleRetryTick = MutableStateFlow(0)

    fun retrySubtitles() {
        _subtitleRetryTick.value += 1
    }

    // 首帧即发 Loading 占位：combine 不等字幕网络，track/进度秒出，字幕到了再补。
    // 否则打开播放页必先闪一帧“未播放”（uiState 初始值 track=null），字幕越慢闪得越明显。
    private val subtitleFlow: Flow<SubState> =
        combine(trackFlow, selectedSubtitleIndex, _subtitleRetryTick, isLoggedIn) { track, idx, _, loggedIn ->
            Triple(track, idx, loggedIn)
        }
            .flatMapLatest { (track, idx, loggedIn) ->
                if (track == null) {
                    flowOf(SubState(status = SubtitleLoadState.Empty))
                } else if (!loggedIn) {
                    flowOf(SubState(status = SubtitleLoadState.NoAuth))
                } else {
                    flow {
                        emit(SubState(status = SubtitleLoadState.Loading))
                        // SWR：磁盘快照先秒显（冷启动恢复播放零等待），新鲜快照直接免网络；
                        // 失败/过期才走网络，有旧快照时失败也不闪错误（静默保留旧字幕）。
                        val cached = subtitles.cached(track.bvid, track.cid)
                        val cachedTracks = cached?.tracks.orEmpty()
                        val cachedHit = cached != null && idx == cached.index && cached.lines.isNotEmpty()
                        if (cachedHit) {
                            emit(SubState(cachedTracks, cached.index, cached.lines, cached.label, cached.isAi, SubtitleLoadState.Ready))
                        } else if (cachedTracks.isNotEmpty()) {
                            // 轨列表秒出（切轨/旧快照），正文按需拉
                            emit(SubState(cachedTracks, idx.coerceIn(0, (cachedTracks.size - 1).coerceAtLeast(0)), status = SubtitleLoadState.Loading))
                        }
                        if (cachedHit && cached.isFresh()) return@flow
                        when (val tr = subtitles.tracksResult(track.bvid, track.cid)) {
                            is Result.Error -> if (cached == null) emit(SubState(status = SubtitleLoadState.Error))
                            is Result.Success -> {
                                val tracks = tr.data
                                if (tracks.isEmpty()) {
                                    emit(SubState(status = SubtitleLoadState.Empty))
                                } else {
                                    val safeIdx = idx.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
                                    val chosen = tracks.getOrNull(safeIdx)
                                    val lr = chosen?.let { subtitles.linesResult(it.url) }
                                    if (lr is Result.Success && lr.data.isNotEmpty()) {
                                        subtitles.store(track.bvid, track.cid, tracks, safeIdx, lr.data, chosen!!.displayLabel, chosen.isAi)
                                        emit(SubState(tracks, safeIdx, lr.data, chosen.displayLabel, chosen.isAi, SubtitleLoadState.Ready))
                                    } else if (lr is Result.Error) {
                                        if (cached == null) emit(SubState(tracks, safeIdx, status = SubtitleLoadState.Error))
                                    } else {
                                        // 正文为空按没字幕处理，label 也不带（与 getSubtitle 语义一致）
                                        emit(SubState(tracks, safeIdx, status = SubtitleLoadState.Empty))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .onStart { emit(SubState()) }

    init {
        viewModelScope.launch {
            trackFlow.collect { selectedSubtitleIndex.value = 0 }
        }
    }

    /**
     * 低频：曲目/队列/播放态/步进/倍速。position 每 500ms 变一次，
     * 但 distinct 后仅实质变化才下发——封面/标题/控制区不再跟 tick 重组。
     * （uiState 保留给单测与事件函数，屏幕不再收它。）
     */
    data class TrackUiState(
        val track: cn.debubu.tingbili.core.data.model.Track? = null,
        val queue: List<cn.debubu.tingbili.core.data.model.Track> = emptyList(),
        val currentIndex: Int = -1,
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val sourceTitle: String? = null,
        val speed: Float = 1f,
        val repeatMode: Int = 0,
        val stepSec: Int = 15,
    )

    val trackUi: StateFlow<TrackUiState> = combine(player.state, prefs.stepSec) { p, stepSec ->
        TrackUiState(
            track = p.currentTrack,
            queue = p.queue,
            currentIndex = p.currentIndex,
            isPlaying = p.isPlaying,
            isLoading = p.isLoading,
            errorMessage = p.errorMessage,
            sourceTitle = p.sourceTitle,
            speed = p.speed,
            repeatMode = p.repeatMode,
            stepSec = stepSec,
        )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackUiState())

    /**
     * 高频：进度三件套 + 睡眠倒计时。500ms 一推，只有进度条/辅助栏订阅，
     * 不再带着封面/歌词整页重组。
     */
    data class ProgressUiState(
        val positionMs: Long = 0L,
        val bufferedPositionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isLoading: Boolean = false,
        val sleepRemainSec: Int? = null,
        val sleepTotalSec: Int? = null,
        val sleepFinishChapter: Boolean = false,
        val sleepEndOfChapter: Boolean = false,
    )

    val progressUi: StateFlow<ProgressUiState> = player.state
        .map { p ->
            ProgressUiState(
                positionMs = p.positionMs,
                bufferedPositionMs = p.bufferedPositionMs,
                durationMs = p.durationMs,
                isLoading = p.isLoading,
                sleepRemainSec = p.sleepRemainSec,
                sleepTotalSec = p.sleepTotalSec,
                sleepFinishChapter = p.sleepFinishChapter,
                sleepEndOfChapter = p.sleepEndOfChapter,
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProgressUiState())

    /**
     * 中频：歌词区。position 每 tick 都参与算行号（二分），
     * 但 distinct 后只在行号/字幕态变化时下发——歌词按行刷，不按 tick 刷。
     */
    data class LyricUiState(
        val lyrics: List<LyricLine> = emptyList(),
        val currentLyricIndex: Int = -1,
        val subtitleLoadState: SubtitleLoadState = SubtitleLoadState.Loading,
        val subtitleLabel: String? = null,
        val subtitleIsAi: Boolean = false,
        val subtitleTracks: List<cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack> = emptyList(),
        val subtitleIndex: Int = 0,
        val subtitleScale: Float = 1f,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val lyricUi: StateFlow<LyricUiState> = combine(
        player.state.map { it.positionMs }.distinctUntilChanged(),
        subtitleFlow,
        prefs.subtitleScale,
    ) { positionMs, sub, subtitleScale ->
        val lyrics = sub.lines
        val idx = if (lyrics.isEmpty()) -1 else LyricState(lyrics).indexFor(positionMs)
        LyricUiState(
            lyrics = lyrics,
            currentLyricIndex = idx,
            subtitleLoadState = sub.status,
            subtitleLabel = sub.label,
            subtitleIsAi = sub.isAi,
            subtitleTracks = sub.tracks,
            subtitleIndex = sub.index,
            subtitleScale = subtitleScale,
        )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricUiState())

    /**
     * 全量聚合（单测与旧调用兼容用）：生产屏幕改收 trackUi/progressUi/lyricUi，
     * 此处无生产订阅者；事件函数一律读 player.state，禁止回退到 uiState.value。
     */
    val uiState: StateFlow<PlayerUiState> = combine(player.state, subtitleFlow, prefs.stepSec, prefs.subtitleScale) { p, sub, stepSec, subtitleScale ->
        val lyrics = sub.lines
        val idx = if (lyrics.isEmpty()) -1 else LyricState(lyrics).indexFor(p.positionMs)
        PlayerUiState(
            isPlaying = p.isPlaying,
            isLoading = p.isLoading,
            errorMessage = p.errorMessage,
            sourceTitle = p.sourceTitle,
            positionMs = p.positionMs,
            bufferedPositionMs = p.bufferedPositionMs,
            durationMs = p.durationMs,
            stepSec = stepSec,
            subtitleScale = subtitleScale,
            speed = p.speed,
            repeatMode = p.repeatMode,
            track = p.currentTrack,
            queue = p.queue,
            currentIndex = p.currentIndex,
            lyrics = lyrics,
            currentLyricIndex = idx,
            subtitleLoadState = sub.status,
            subtitleLabel = sub.label,
            subtitleIsAi = sub.isAi,
            subtitleTracks = sub.tracks,
            subtitleIndex = sub.index,
            sleepRemainSec = p.sleepRemainSec,
            sleepTotalSec = p.sleepTotalSec,
            sleepFinishChapter = p.sleepFinishChapter,
            sleepEndOfChapter = p.sleepEndOfChapter
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    // ---- 播放控制（委托 PlayerManager）----

    fun toggle() = player.toggle()

    fun previous() = player.previous()

    fun next() = player.next()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun skipBackward() {
        viewModelScope.launch { player.seekStep(-1) }
    }

    fun skipForward() {
        viewModelScope.launch { player.seekStep(1) }
    }

    /** 点击歌词行跳转到该行时间 */
    fun seekToLyric(line: LyricLine) = player.seekTo(line.timeMs)

    /** 字幕轨切换：越界由管线钳制 */
    fun selectSubtitle(index: Int) {
        selectedSubtitleIndex.value = index
    }

    /** 章节点播（读常热的 player.state：uiState 自屏幕改收三层流后已无生产订阅者，读它会拿到过期初值） */
    fun playAt(index: Int) {
        val s = player.state.value
        if (index !in s.queue.indices) return
        viewModelScope.launch { player.play(s.queue, index, s.sourceTitle) }
    }

    /** “到点后播完本章再停”默认开关（持久化，下次弹窗沿用） */
    val sleepFinishDefault: StateFlow<Boolean> = prefs.sleepFinishChapter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 上次所选定时分钟（滚轮初值，下次打开沿用） */
    val sleepLastMin: StateFlow<Int> = prefs.sleepLastMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    /** 睡眠定时透传（Manager 内内存态，触发自带落盘）；启动即记住分钟+开关偏好 */
    fun startSleepTimer(seconds: Int, finishChapter: Boolean = false) {
        viewModelScope.launch {
            prefs.setSleepFinishChapter(finishChapter)
            prefs.setSleepLastMinutes(seconds / 60)
        }
        player.startSleepTimer(seconds, finishChapter)
    }

    /** 倒计时进行中改开关（同步 Manager + 记住偏好） */
    fun setSleepFinishChapter(v: Boolean) {
        viewModelScope.launch { prefs.setSleepFinishChapter(v) }
        player.setSleepFinishChapter(v)
    }

    fun cancelSleepTimer() = player.cancelSleepTimer()

    /** 刷新转圈（顶栏 ⋮ 变菊花，防连点） */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 一次性提示（Detail 页同款：界面 Toast 后调 consumeMessage 清掉） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() {
        _message.value = null
    }

    /**
     * 刷新当前 BV：分 P 增减/改名不断播对齐 + 字幕缓存作废重拉。
     * 失败绝不清空旧队列（Toast 报“已保留旧数据”）。
     */
    fun refreshBv() {
        if (_isRefreshing.value) return
        // 同上：读 player.state 而非 uiState，避免无订阅者时的过期初值导致静默 return
        val cur = player.state.value.currentTrack ?: return
        if (player.state.value.isLoading) {
            _message.value = "正在加载，稍后再试"
            return
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                when (val r = repo.getViewDetail(cur.bvid)) {
                    is Result.Error -> _message.value = "刷新失败，已保留旧数据"
                    is Result.Success -> {
                        val res = player.refreshQueue(r.data.toTracks(), r.data.title)
                        // 字幕可能后补/改版：作废该 BV 缓存，管线重走按需重拉
                        subtitles.invalidateBv(cur.bvid)
                        retrySubtitles()
                        _message.value = when {
                            res.added > 0 -> "已更新，新增 ${res.added} 集"
                            res.removed > 0 || res.updated > 0 -> "已更新到最新"
                            else -> "已是最新"
                        }
                    }
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** 倍速循环切换：0.5 → 1.0 → 1.5 → 2.0 → 3.0 */
    fun cycleSpeed() {
        val steps = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
        val cur = player.state.value.speed
        val next = steps.firstOrNull { it > cur + 0.01f } ?: steps.first()
        viewModelScope.launch { player.setSpeed(next) }
    }
}
