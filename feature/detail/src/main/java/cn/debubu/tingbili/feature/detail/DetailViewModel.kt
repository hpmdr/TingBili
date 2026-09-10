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

    /** 加入听单选择面板 */
    private val _showPlaylistPicker = MutableStateFlow(false)
    val showPlaylistPicker: StateFlow<Boolean> = _showPlaylistPicker.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val playlists: StateFlow<List<PlaylistEntity>> = _playlists.asStateFlow()

    /** 一次性提示消息（Toast），消费后置空 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        load()
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

    /** 从第 index 个分 P 开始播放 */
    fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        viewModelScope.launch { player.play(tracks, index) }
    }

    /** 打开加入听单面板，刷新现有听单列表 */
    fun showPlaylistPicker() {
        viewModelScope.launch {
            _playlists.value = playlistDao.getPlaylists()
            _showPlaylistPicker.value = true
        }
    }

    fun dismissPlaylistPicker() {
        _showPlaylistPicker.value = false
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 加入已有听单，封面为空时自动用首条视频封面补齐 */
    fun addToPlaylist(playlist: PlaylistEntity, tracks: List<Track>) {
        viewModelScope.launch {
            val added = insertTracks(playlist.id, tracks)
            // 听单封面为空时，用本次加入的视频封面或详情页封面补齐
            maybeFillCover(playlist.id, tracks)
            _showPlaylistPicker.value = false
            _message.value = if (added > 0) "已加入听单「${playlist.name}」（$added 集）" else "已在听单「${playlist.name}」中"
        }
    }

    /** 新建听单并加入（听单名 = 输入名，默认用视频标题），封面取视频封面 */
    fun createPlaylistAndAdd(name: String, tracks: List<Track>) {
        viewModelScope.launch {
            val playlistName = name.ifBlank { "听单" }
            val videoCover = (state.value as? DetailUiState.Success)?.video?.pic
            val cover = tracks.firstOrNull { it.cover.isNotBlank() }?.cover ?: videoCover
            val pid = playlistDao.insert(PlaylistEntity(name = playlistName, cover = cover?.takeIf { it.isNotBlank() }))
            insertTracks(pid, tracks)
            _showPlaylistPicker.value = false
            _message.value = "已创建听单「$playlistName」并加入 ${tracks.size} 集"
        }
    }

    /** 追加分 P 到听单，order 接在已有曲目之后；重复曲目跳过，返回实际新增数量 */
    private suspend fun insertTracks(playlistId: Long, tracks: List<Track>): Int {
        if (tracks.isEmpty()) return 0
        val existing = playlistDao.getTracks(playlistId)
        val existingKeys = existing.map { it.bvid to it.cid }.toSet()
        var order = existing.size
        var added = 0
        tracks.forEach { t ->
            if ((t.bvid to t.cid) !in existingKeys) {
                playlistDao.addTrack(
                    PlaylistTrackEntity(
                        playlistId = playlistId,
                        bvid = t.bvid,
                        cid = t.cid,
                        title = t.title,
                        order = order
                    )
                )
                order++
                added++
            }
        }
        return added
    }

    private suspend fun maybeFillCover(playlistId: Long, tracks: List<Track>) {
        val current = try { playlistDao.getPlaylist(playlistId) } catch (_: Exception) { null } ?: return
        if (!current.cover.isNullOrBlank()) return
        val cover = tracks.firstOrNull { it.cover.isNotBlank() }?.cover
            ?: (state.value as? DetailUiState.Success)?.video?.pic?.takeIf { it.isNotBlank() }
            ?: return
        try { playlistDao.updateCover(playlistId, cover) } catch (_: Exception) { }
    }
}
