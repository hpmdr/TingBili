package cn.debubu.tingbili.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Success(val video: ViewData, val tracks: List<Track>) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository,
    private val player: PlayerManager,
    private val playlistDao: PlaylistDao
) : ViewModel() {

    val bvid: String = checkNotNull(savedStateHandle["bvid"])

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

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
        viewModelScope.launch { player.play(tracks, index) }
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
