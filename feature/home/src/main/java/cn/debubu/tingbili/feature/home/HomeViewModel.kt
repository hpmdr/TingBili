package cn.debubu.tingbili.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.HomeSnapshotDao
import cn.debubu.tingbili.core.data.db.HomeSnapshotEntity
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.core.ui.HomeScrollTopBus
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BiliRepository,
    private val prefs: PreferencesRepository,
    private val player: PlayerManager,
    private val playlistDao: PlaylistDao,
    private val homeSnapshotDao: HomeSnapshotDao,
    private val scrollTopBus: HomeScrollTopBus,
    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
) : ViewModel() {

    /** 回顶事件（底栏/Rail 双击推荐时 +1，首页收到即滚到顶） */
    val scrollTopTick: StateFlow<Int> = scrollTopBus.tick

    // 数据层常驻：流的生命周期与 ViewModel 对齐（Eagerly 启动，VM 释放才停），
    // 与有没有收集器无关。进搜索/详情/切 Tab 再回来都是秒回、无网络、无转圈；
    // 只有关键词变更、下拉刷新、进程重建才会重建推荐流。
    /** 首页策展关键词（设置页可增减），供搜索框展示 */
    val homeKeywords: StateFlow<List<String>> = prefs.homeKeywords.stateIn(
        viewModelScope, SharingStarted.Eagerly, HomePagingSource.DEFAULT_RECOMMEND_KEYWORDS
    )

    /**
     * SWR 快照：冷启动秒开上次首屏，后台 Paging 刷新成功后覆盖。
     * 为空 = 无缓存/解析失败，UI 走纯 Paging 链路；非空时 Paging 失败也要保列表。
     */
    private val _snapshotTracks = MutableStateFlow<List<Track>?>(null)
    val snapshotTracks: StateFlow<List<Track>?> = _snapshotTracks.asStateFlow()

    private val _snapshotUpdatedAt = MutableStateFlow<Long?>(null)
    val snapshotUpdatedAt: StateFlow<Long?> = _snapshotUpdatedAt.asStateFlow()

    /** 快照是否过期（TTL 外仍展示，只用于“正在更新”提示文案） */
    fun isSnapshotStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        val ts = _snapshotUpdatedAt.value ?: return true
        return nowMs - ts > HOME_SNAPSHOT_TTL_MS
    }

    /**
     * 冷启动不自动刷新：有快照就直接看上次的，用户下拉才走网络；
     * 无快照（首次安装/被清）才自动起 Paging。省流量，快照稳定可见。
     */
    private val _pagingStarted = MutableStateFlow(false)
    val pagingStarted: StateFlow<Boolean> = _pagingStarted.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val entity = homeSnapshotDao.get()
                val tracks = if (entity != null) {
                    try {
                        json.decodeFromString<List<Track>>(entity.tracksJson)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else emptyList()
                if (tracks.isNotEmpty()) {
                    _snapshotTracks.value = tracks.take(MAX_SNAPSHOT_TRACKS)
                    _snapshotUpdatedAt.value = entity!!.updatedAt
                    _pagingStarted.value = false
                } else {
                    _pagingStarted.value = true
                }
            } catch (_: Exception) {
                // 快照读失败不打断首页，纯当无缓存：自动起 Paging
                _pagingStarted.value = true
            }
        }
    }

    /** 下拉刷新计数：每次 +1 即随机重排关键词并重建推荐流 */
    private val _refreshTick = MutableStateFlow(0)
    private var lastKeywordOrder: List<String> = emptyList()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow: StateFlow<PagingData<Track>> =
        combine(prefs.homeKeywords, _refreshTick, _pagingStarted) { kws, _, started ->
            // 随机重排，多词时尽量与上次不同，避免“刷了个寂寞”
            var shuffled = kws.shuffled()
            if (shuffled.size > 1 && shuffled == lastKeywordOrder) shuffled = kws.shuffled()
            lastKeywordOrder = shuffled
            shuffled to started
        }
            .flatMapLatest { (ordered, started) ->
                if (!started) {
                    // 有快照的冷启动：不建 Pager、不碰网络，只看缓存
                    flowOf(PagingData.empty())
                } else {
                    // maxSize 显式有界：默认不清旧页（Track 很小但页多了也是堆），
                    // 200 条后自动丢远离视口的页，内存不随滑动涨。
                    Pager(PagingConfig(pageSize = 20, enablePlaceholders = false, maxSize = 200)) {
                        HomePagingSource(repo, ordered) { tracks, orderedKeywords ->
                            saveSnapshot(tracks, orderedKeywords)
                        }
                    }.flow.cachedIn(viewModelScope)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PagingData.empty())

    /** 用户手动刷新（下拉）：开闸放行 Paging，随机重排关键词并重建推荐流（快照不清，失败时还能兜底） */
    fun shuffleKeywords() {
        _pagingStarted.value = true
        _refreshTick.value += 1
    }

    /** 快照底部“加载更多”：只开闸不换词，切到 Paging 流继续往后翻（与下拉同流，不重排） */
    fun startPaging() {
        _pagingStarted.value = true
    }

    private fun saveSnapshot(tracks: List<Track>, orderedKeywords: List<String>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                val bounded = tracks.take(MAX_SNAPSHOT_TRACKS)
                homeSnapshotDao.save(
                    HomeSnapshotEntity(
                        keywordsJson = json.encodeToString<List<String>>(orderedKeywords),
                        tracksJson = json.encodeToString<List<Track>>(bounded),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (_: Exception) {
                // 落盘失败静默，首页不受影响
            }
        }
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

    companion object {
        /** 快照 TTL：15min 内不提示过期，超期仍展示、后台静默刷 */
        const val HOME_SNAPSHOT_TTL_MS = 15 * 60 * 1000L
        /** 快照上限：只存首屏 60 条，有界清理 */
        const val MAX_SNAPSHOT_TRACKS = 60
    }
}
