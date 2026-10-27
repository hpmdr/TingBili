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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    /**
     * 当前列表的搜索词：列表内容永远等于“这个词的搜索结果”。
     * 初始值 = 上次搜索词（没有则首个推荐词）——“推荐”只是自动执行了一次同样的搜索。
     * 空串 = 冷启动还在读偏好，此时不建 Pager。
     */
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    /** 当前词的命中总数（接口返回的全量条数），结果计数条用 */
    private val _total = MutableStateFlow<Int?>(null)
    val total: StateFlow<Int?> = _total.asStateFlow()

    /** 首页策展关键词（设置页可增减）：搜索建议词 + 没有搜索历史时的兜底词 */
    val homeKeywords: StateFlow<List<String>> = prefs.homeKeywords.stateIn(
        viewModelScope, SharingStarted.Eagerly, HomePagingSource.DEFAULT_RECOMMEND_KEYWORDS
    )

    /** 搜索历史（本地，最近在前），建议区展示；第一条即上次搜索词 */
    val searchHistory: StateFlow<List<String>> = prefs.searchHistory.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    /**
     * 执行一次搜索：列表直接换成该词的结果，与冷启动的默认搜索完全等价。
     * 同一个词再搜一次也重建 Pager（当刷新），避免“点了没反应”。
     * 注意每次只动一个 StateFlow（keyword 或 tick），否则 flatMapLatest 会重建两次。
     */
    fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        if (q == _keyword.value) {
            _refreshTick.value += 1
        } else {
            _keyword.value = q
            _total.value = null
        }
        viewModelScope.launch { prefs.addSearchHistory(q) }
    }

    /** 下拉刷新：用当前词重新拉第一页 */
    fun refresh() {
        _refreshTick.value += 1
    }

    fun removeSearchHistory(query: String) {
        viewModelScope.launch { prefs.removeSearchHistory(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { prefs.clearSearchHistory() }
    }

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

    init {
        // 初始词 = 上次搜索 > 首个推荐词 > 兜底；快照只在词一致时才当首屏占位
        viewModelScope.launch(Dispatchers.IO) {
            val kw = runCatching { prefs.searchHistory.first().firstOrNull() }.getOrNull()
                ?: homeKeywords.value.firstOrNull()
                ?: DEFAULT_KEYWORD
            try {
                val entity = homeSnapshotDao.get()
                if (entity != null) {
                    val snapKeyword = runCatching {
                        json.decodeFromString<List<String>>(entity.keywordsJson).firstOrNull()
                    }.getOrNull()
                    val tracks = runCatching {
                        json.decodeFromString<List<Track>>(entity.tracksJson)
                    }.getOrDefault(emptyList())
                    // 旧快照可能是别的词的内容，不能冒充这次的结果
                    if (snapKeyword == kw && tracks.isNotEmpty()) {
                        _snapshotTracks.value = tracks.take(MAX_SNAPSHOT_TRACKS)
                        _snapshotUpdatedAt.value = entity.updatedAt
                    }
                }
            } catch (_: Exception) {
                // 快照读失败不打断首页
            }
            _keyword.value = kw
        }
    }

    /** 下拉刷新/同词重搜计数：+1 即用当前词重建 Pager */
    private val _refreshTick = MutableStateFlow(0)

    // 首页唯一的列表：当前关键词的搜索结果分页（推荐 = 默认词自动搜索，与手动搜索同一条流）
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow: StateFlow<PagingData<Track>> =
        combine(_keyword, _refreshTick) { kw, tick -> kw to tick }
            .flatMapLatest { (kw, _) ->
                if (kw.isBlank()) {
                    flowOf(PagingData.empty())
                } else {
                    // maxSize 显式有界：默认不清旧页（Track 很小但页多了也是堆），
                    // 200 条后自动丢远离视口的页，内存不随滑动涨。
                    // prefetchDistance 收小：默认=pageSize 会提前很多触发 append，
                    // 快速上滑时请求密度太高撞 B 站 412；8 条提前量足够顺滑。
                    Pager(
                        PagingConfig(
                            pageSize = 20,
                            prefetchDistance = 8,
                            initialLoadSize = 20,
                            enablePlaceholders = false,
                            maxSize = 200
                        )
                    ) {
                        SearchPagingSource(repo, kw) { tracks, total ->
                            _total.value = total
                            saveSnapshot(kw, tracks)
                        }
                    }.flow.cachedIn(viewModelScope)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PagingData.empty())

    private fun saveSnapshot(keyword: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        // 60 条 Track JSON 编码 + Room 写放 IO：回调起于 Paging 加载，滚动中别占主线程
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bounded = tracks.take(MAX_SNAPSHOT_TRACKS)
                homeSnapshotDao.save(
                    HomeSnapshotEntity(
                        // 复用字段存词表（单元素）：读取时用它校验快照是不是当前词的内容
                        keywordsJson = json.encodeToString<List<String>>(listOf(keyword)),
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
        /** 没有搜索历史、也没有推荐词时的兜底搜索词 */
        const val DEFAULT_KEYWORD = "有声小说"
    }
}
