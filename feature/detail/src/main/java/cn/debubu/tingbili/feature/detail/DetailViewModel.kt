package cn.debubu.tingbili.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Success(val video: ViewData, val tracks: List<Track>) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

/**
 * 列表里被重点标出的那一项。一个列表同时只会有一个。
 * @param index 分P 下标
 * @param isPlaying true=正在播这一项；false=历史续播点
 */
data class DetailFocus(val index: Int, val isPlaying: Boolean)

/** 正在播放的分P 快照，供列表行渲染「正在播放 + 当前进度」 */
data class PlayingTrack(val key: String, val positionMs: Long)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository,
    private val player: PlayerManager,
    private val playlistDao: PlaylistDao,
    private val historyDao: HistoryDao
) : ViewModel() {

    val bvid: String = checkNotNull(savedStateHandle["bvid"])

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _startingPlay = MutableStateFlow(false)
    private val _pendingIndex = MutableStateFlow<Int?>(null)

    /**
     * 播放启动中，取音源要等网络。接 [PlayerManager.isLoading]，
     * 让「播放全部 / 某个分P」在等待期间有明确反馈并禁止重复点击。
     */
    val startingPlay: StateFlow<Boolean> =
        combine(_startingPlay, player.state) { starting, s -> starting || s.isLoading }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 正在等起播的那一行索引 */
    val pendingIndex: StateFlow<Int?> = _pendingIndex.asStateFlow()

    /** "bvid:cid" -> 播放记录，分P 列表显示「上次听到」用 */
    val progress: StateFlow<Map<String, HistoryEntity>> = historyDao.observeAll()
        .map { list -> list.associateBy { "${it.bvid}:${it.cid}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 正在播放的分P 快照（键 + 整秒进度），用于替代该行的「上次听到」 */
    val playingTrack: StateFlow<PlayingTrack?> = player.state
        .map { s ->
            if (s.isPlaying || s.isLoading) {
                s.currentTrack?.let { PlayingTrack("${it.bvid}:${it.cid}", s.positionMs / 1000L * 1000L) }
            } else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 唯一重点项，同时也是「继续播放」的落点：正在播的这一项优先，
     * 否则取播放记录里最近的一集；都没听过则为 null（按钮回到「播放全部」）。
     */
    val focus: StateFlow<DetailFocus?> =
        combine(state, progress, playingTrack) { s, prog, playing ->
            val list = (s as? DetailUiState.Success)?.tracks ?: return@combine null
            fun keyOf(t: Track) = "${t.bvid}:${t.cid}"
            fun idxOfKey(k: String) = list.indexOfFirst { keyOf(it) == k }.takeIf { i -> i >= 0 }

            playing?.key?.let { idxOfKey(it) }?.let { return@combine DetailFocus(it, isPlaying = true) }

            list.filter { prog.containsKey(keyOf(it)) }
                .maxByOrNull { prog[keyOf(it)]?.updatedAt ?: 0L }
                ?.let { idxOfKey(keyOf(it)) }
                ?.let { DetailFocus(it, isPlaying = false) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        load()
        observeFavorite()
    }

    private fun observeFavorite() {
        viewModelScope.launch {
            playlistDao.observePlaylistByBvid(bvid).collect { entity ->
                _isFavorited.value = entity != null
            }
        }
    }

    fun load() {
        _state.value = DetailUiState.Loading
        viewModelScope.launch {
            when (val result = repo.getViewDetail(bvid)) {
                is Result.Success -> {
                    val video = result.data
                    _state.value = DetailUiState.Success(video, video.toTracks())
                }
                is Result.Error -> _state.value = DetailUiState.Error(result.msg ?: "加载失败")
            }
        }
    }

    fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        // 重复点击在这里就被挡下并保持 loading 反馈，不让互斥锁静默丢弃
        if (_startingPlay.value) return
        viewModelScope.launch {
            _pendingIndex.value = index
            _startingPlay.value = true
            try {
                player.play(tracks, index)
            } finally {
                _startingPlay.value = false
                _pendingIndex.value = null
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val existing = playlistDao.getPlaylistByBvid(bvid)
            if (existing != null) {
                playlistDao.deletePlaylist(existing.id)
                playlistDao.clearTracks(existing.id)
                _message.value = "已取消收藏"
            } else {
                val current = state.value as? DetailUiState.Success ?: run {
                    _message.value = "视频信息尚未加载"
                    return@launch
                }
                val video = current.video
                val tracks = current.tracks
                val cover = tracks.firstOrNull { it.cover.isNotBlank() }?.cover ?: video.pic
                val pid = playlistDao.insert(
                    PlaylistEntity(
                        name = video.title.ifBlank { "收藏 ${video.bvid}" },
                        cover = cover?.takeIf { it.isNotBlank() },
                        sourceBvid = bvid,
                        kind = "bv"
                    )
                )
                var order = 0
                tracks.forEach { t ->
                    playlistDao.addTrack(
                        PlaylistTrackEntity(
                            playlistId = pid,
                            bvid = t.bvid,
                            cid = t.cid,
                            title = t.title,
                            order = order++,
                            author = t.author,
                            cover = t.cover,
                            durationMs = t.durationMs,
                            videoTitle = t.videoTitle,
                            pageIndex = t.pageIndex
                        )
                    )
                }
                _message.value = "已收藏（${tracks.size} 集）"
            }
        }
    }
}
