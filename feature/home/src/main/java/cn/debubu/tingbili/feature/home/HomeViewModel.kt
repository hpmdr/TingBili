package cn.debubu.tingbili.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BiliRepository,
    private val player: PlayerManager,
    private val playlistDao: PlaylistDao
) : ViewModel() {

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow: Flow<PagingData<Track>> = _keyword.flatMapLatest { kw ->
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            BiliPagingSource(repo, kw)
        }.flow.cachedIn(viewModelScope)
    }

    // BV multi-P bottomSheet state
    private val _selectedBvid = MutableStateFlow<String?>(null)
    val selectedBvid: StateFlow<String?> = _selectedBvid.asStateFlow()

    private val _expandedTracks = MutableStateFlow<List<Track>?>(null)
    val expandedTracks: StateFlow<List<Track>?> = _expandedTracks.asStateFlow()

    private val _showBottomSheet = MutableStateFlow(false)
    val showBottomSheet: StateFlow<Boolean> = _showBottomSheet.asStateFlow()

    private val _isLoadingBv = MutableStateFlow(false)
    val isLoadingBv: StateFlow<Boolean> = _isLoadingBv.asStateFlow()

    private val _bvError = MutableStateFlow<String?>(null)
    val bvError: StateFlow<String?> = _bvError.asStateFlow()

    fun onSearch(kw: String) {
        _keyword.value = kw
    }

    /** Alias per brief: search(keyword) triggers paging */
    fun search(keyword: String) = onSearch(keyword)

    fun onTrackClick(track: Track, bvMulti: Boolean = false) {
        if (bvMulti) {
            showBottomSheetForBv(track.bvid)
        } else {
            // Default behavior: if we know multi-P, show sheet; else direct play.
            // For discovery, many BV may be multi-P but unknown until view() — offer sheet anyway
            // If caller doesn't specify, we treat single play as direct; bottomSheet still available via long-press
            // Here we play directly per brief's else branch
            viewModelScope.launch { player.play(listOf(track), 0) }
        }
    }

    /** Called when user taps a BV item to expand multi-P handling */
    fun showBottomSheetForBv(bvid: String) {
        if (bvid.isBlank()) return
        _selectedBvid.value = bvid
        _showBottomSheet.value = true
        _isLoadingBv.value = true
        _bvError.value = null
        _expandedTracks.value = null
        viewModelScope.launch {
            when (val r = repo.getView(bvid)) {
                is Result.Success -> {
                    _expandedTracks.value = r.data
                    _isLoadingBv.value = false
                }
                is Result.Error -> {
                    _bvError.value = r.msg
                    _isLoadingBv.value = false
                }
            }
        }
    }

    fun dismissBottomSheet() {
        _showBottomSheet.value = false
        _selectedBvid.value = null
        _expandedTracks.value = null
        _bvError.value = null
        _isLoadingBv.value = false
    }

    fun playTracks(tracks: List<Track>, index: Int = 0) {
        if (tracks.isEmpty()) return
        viewModelScope.launch { player.play(tracks, index) }
    }

    fun playSingle(track: Track) = playTracks(listOf(track), 0)

    suspend fun addToPlaylist(playlistId: Long, track: Track) {
        playlistDao.addTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                bvid = track.bvid,
                cid = track.cid,
                title = track.title,
                order = 0
            )
        )
    }

    suspend fun addToPlaylist(playlistId: Long, tracks: List<Track>) {
        tracks.forEach { t ->
            playlistDao.addTrack(
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    bvid = t.bvid,
                    cid = t.cid,
                    title = t.title,
                    order = 0
                )
            )
        }
    }

    /**
     * Convenience: add single track to playlist; creates default playlist if none exists.
     * Used by bottomSheet “单P加入” / “整集合集加入歌单”.
     */
    fun addToPlaylist(track: Track) {
        viewModelScope.launch {
            val pid = ensureDefaultPlaylist()
            addToPlaylist(pid, track)
        }
    }

    fun addToPlaylist(tracks: List<Track>) {
        viewModelScope.launch {
            val pid = ensureDefaultPlaylist()
            addToPlaylist(pid, tracks)
        }
    }

    /** Back-compat alias per brief: addToPlaylist(trackOrBv) */
    fun addBvToPlaylist(bvid: String, playlistId: Long) {
        val tracks = _expandedTracks.value?.filter { it.bvid == bvid } ?: return
        viewModelScope.launch { addToPlaylist(playlistId, tracks) }
    }

    private suspend fun ensureDefaultPlaylist(): Long {
        val existing = playlistDao.getPlaylists()
        if (existing.isNotEmpty()) return existing.first().id
        return playlistDao.insert(PlaylistEntity(name = "默认歌单"))
    }
}
