package cn.debubu.tingbili.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BiliRepository,
    private val prefs: PreferencesRepository,
    private val player: PlayerManager,
    private val playlistDao: PlaylistDao
) : ViewModel() {

    // 数据层常驻：流的生命周期与 ViewModel 对齐（Eagerly 启动，VM 释放才停），
    // 与有没有收集器无关。进搜索/详情/切 Tab 再回来都是秒回、无网络、无转圈；
    // 只有关键词变更、下拉刷新、进程重建才会重建推荐流。
    /** 首页策展关键词（设置页可增减），供搜索框展示 */
    val homeKeywords: StateFlow<List<String>> = prefs.homeKeywords.stateIn(
        viewModelScope, SharingStarted.Eagerly, HomePagingSource.DEFAULT_RECOMMEND_KEYWORDS
    )

    /** 下拉刷新计数：每次 +1 即随机重排关键词并重建推荐流 */
    private val _refreshTick = MutableStateFlow(0)
    private var lastKeywordOrder: List<String> = emptyList()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow: StateFlow<PagingData<Track>> =
        combine(prefs.homeKeywords, _refreshTick) { kws, _ ->
            // 随机重排，多词时尽量与上次不同，避免“刷了个寂寞”
            var shuffled = kws.shuffled()
            if (shuffled.size > 1 && shuffled == lastKeywordOrder) shuffled = kws.shuffled()
            lastKeywordOrder = shuffled
            shuffled
        }
            .flatMapLatest { ordered ->
                Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                    HomePagingSource(repo, ordered)
                }.flow.cachedIn(viewModelScope)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PagingData.empty())

    /** 下拉刷新：随机重排关键词，推荐流自动重建 */
    fun shuffleKeywords() {
        _refreshTick.value += 1
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
                order = 0,
                author = track.author,
                cover = track.cover,
                durationMs = track.durationMs,
                videoTitle = track.videoTitle,
                pageIndex = track.pageIndex
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
                    order = 0,
                    author = t.author,
                    cover = t.cover,
                    durationMs = t.durationMs,
                    videoTitle = t.videoTitle,
                    pageIndex = t.pageIndex
                )
            )
        }
    }

    /**
     * Convenience: add single track to playlist; creates default playlist if none exists.
     * Used by bottomSheet “单P加入” / “整集合集加入收藏”.
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
        return playlistDao.insert(PlaylistEntity(name = "默认收藏"))
    }
}
