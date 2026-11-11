package cn.debubu.tingbili.feature.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerHandle
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.HomeSnapshotDao
import cn.debubu.tingbili.core.data.db.HomeSnapshotEntity
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedItem
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SearchData
import cn.debubu.tingbili.data.bilibili.dto.SearchItem
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.ViewPage
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    @get:Rule
    val instantTask = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    // --- fakes ---
    class FakeBiliApi : BiliApi {
        var lastSearchKeyword: String = ""
        var searchResult: List<SearchItem> = listOf(
            SearchItem(bvid = "BV1xx", title = "音乐测试", author = "up1", pic = "https://cover", duration = "3:00"),
            SearchItem(bvid = "BV2yy", title = "音乐测试2", author = "up2", pic = "https://cover2", duration = "4:00")
        )
        /** 第 2 页搜索回补结果（种子池耗尽回补场景用） */
        var page2Result: List<SearchItem> = emptyList()
        /** 搜索页码调用记录 */
        val searchPageCalls = mutableListOf<Int>()
        /** related 固定回包（对所有 bvid 返回同一批） */
        var relatedResult: List<RelatedItem> = emptyList()
        var relatedCalls = 0
        var viewResult: ViewDto = ViewDto(
            code = 0, data = ViewData(
                bvid = "BV1xx", title = "My Video", pic = "https://cover",
                owner = cn.debubu.tingbili.data.bilibili.dto.Owner(name = "author1"),
                pages = listOf(ViewPage(cid = 1001, page = 1, part = "P1", duration = 60), ViewPage(cid = 1002, page = 2, part = "P2", duration = 120))
            )
        )
        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto {
            lastSearchKeyword = keyword
            searchPageCalls.add(page)
            val data = when {
                page == 1 -> SearchData(result = searchResult, page = page, numResults = searchResult.size, numPages = 1)
                page == 2 && page2Result.isNotEmpty() -> SearchData(result = page2Result, page = page, numResults = page2Result.size, numPages = 2)
                else -> SearchData(result = emptyList(), page = page)
            }
            return SearchDto(code = 0, data = data)
        }
        override suspend fun view(bvid: String): ViewDto = viewResult
            override suspend fun related(bvid: String): RelatedDto {
            relatedCalls++
            return RelatedDto(code = 0, data = relatedResult)
        }
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int, qn: Int?, guest: Boolean): PlayUrlDto = PlayUrlDto(code = 0, data = cn.debubu.tingbili.data.bilibili.dto.PlayUrlData(dash = cn.debubu.tingbili.data.bilibili.dto.DashData(audio = listOf(cn.debubu.tingbili.data.bilibili.dto.DashAudio(baseUrl = "https://audio.example.com/a.mp3")))))
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
        override suspend fun favFoldersAll(upMid: Long): cn.debubu.tingbili.data.bilibili.dto.FavFolderDto = cn.debubu.tingbili.data.bilibili.dto.FavFolderDto()
        override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): cn.debubu.tingbili.data.bilibili.dto.FavDetailDto = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto()
        override suspend fun toview(pn: Int, ps: Int): cn.debubu.tingbili.data.bilibili.dto.ToviewDto = cn.debubu.tingbili.data.bilibili.dto.ToviewDto()
        override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto = cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto()
    }

    class FakePlayerHandle : PlayerHandle {
        var mediaItems: List<MediaItem> = emptyList()
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override val currentMediaItemIndex: Int get() = 0
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override val isPlaying: Boolean get() = false
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) { mediaItems = items }
        override fun replaceMediaItem(index: Int, item: MediaItem) { if (index in mediaItems.indices) mediaItems = mediaItems.toMutableList().also { it[index] = item } }

        override val mediaItemCount: Int get() = mediaItems.size
        override fun addMediaItem(index: Int, item: MediaItem) {
            mediaItems = mediaItems.toMutableList().also { it.add(index.coerceIn(0, it.size), item) }
        }
        override fun removeMediaItem(index: Int) {
            mediaItems = mediaItems.toMutableList().also { if (index in it.indices) it.removeAt(index) }
        }
        override fun prepare() {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun addListener(listener: Player.Listener) {}
        override fun release() {}
    }

    class FakePlayUrlCacheDao : cn.debubu.tingbili.core.data.db.PlayUrlCacheDao {
        val map = mutableMapOf<String, cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>()
        override suspend fun get(key: String): cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity? = map[key]
        override suspend fun getAll(): List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity> = map.values.toList()
        override suspend fun putAll(rows: List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>) {
            rows.forEach { map[it.key] = it }
        }
        override suspend fun pruneExpired(cutoffMs: Long) {
            map.entries.removeIf { it.value.fetchedAtMs < cutoffMs }
        }
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun pruneOverflow(keep: Int) {
            map.values.sortedByDescending { it.fetchedAtMs }.drop(keep).map { it.key }.forEach { map.remove(it) }
        }
    }


    class FakePlayQueueDao : cn.debubu.tingbili.core.data.db.PlayQueueDao {
        var snapshot: cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity? = null
        override suspend fun get(): cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity? = snapshot
        override suspend fun save(s: cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity) {
            snapshot = s
        }
        override suspend fun clear() {
            snapshot = null
        }
    }

    class FakeHistoryDao : HistoryDao {
        override suspend fun insertAll(rows: List<HistoryEntity>) {}
        override suspend fun save(e: HistoryEntity) {}
        override suspend fun getSource(sourceType: Int, sourceId: String): HistoryEntity? = null
        override suspend fun getLatestForTrack(bvid: String, cid: Long): HistoryEntity? = null
        override suspend fun getAll(): List<HistoryEntity> = emptyList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTotalListenMs(): Flow<Long> = flowOf(0L)
        override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTodayCount(todayStart: Long): Flow<Int> = flowOf(0)
        override suspend fun deleteSource(sourceType: Int, sourceId: String) {}
        override suspend fun clearAll() {}
    }

    // fake 同时实现 DAO 与仓储：VM 用仓储，测试内部逻辑复用同一份状态
class FakeHomeSnapshotDao : HomeSnapshotDao, cn.debubu.tingbili.core.data.repository.HomeSnapshotRepository {
        var entity: HomeSnapshotEntity? = null
        override suspend fun get(): HomeSnapshotEntity? = entity
        override fun observe(): Flow<HomeSnapshotEntity?> = flowOf(entity)
        override suspend fun save(e: HomeSnapshotEntity) { entity = e }
        override suspend fun clear() { entity = null }
    }

    private lateinit var fakeApi: FakeBiliApi
    private lateinit var repo: BiliRepository
    private lateinit var player: PlayerManager
    private lateinit var snapshotDao: FakeHomeSnapshotDao
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fakeApi = FakeBiliApi()
        repo = BiliRepository(fakeApi)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesRepository(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile("test_prefs_${System.nanoTime()}")
            }
        )
        val resolvers = cn.debubu.tingbili.data.bilibili.BiliMediaResolvers(repo, prefs)
        player = PlayerManager(FakePlayerHandle(), FakeHistoryDao(), prefs, resolvers, resolvers, resolvers, FakePlayUrlCacheDao(), FakePlayQueueDao(), context)
        snapshotDao = FakeHomeSnapshotDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `home exposes recommended paging data`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        val pagingData = vm.pagingFlow.first()
        assertNotNull(pagingData)
    }

    @Test
    fun `refresh emits new paging data`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        vm.pagingFlow.first()
        vm.refresh()
        // 常驻流：刷新后必有新一份 PagingData
        assertNotNull(vm.pagingFlow.drop(1).first())
    }

    @Test
    fun `snapshot with matching keyword is used as first screen`() = runTest(dispatcher) {
        prefs.clearSearchHistory()
        val tracks = listOf(Track("BV1xx", 1001, "T1", "a", "", 60000, null))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        snapshotDao.entity = HomeSnapshotEntity(
            keywordsJson = json.encodeToString(listOf("有声小说")),
            tracksJson = json.encodeToString(tracks),
            updatedAt = System.currentTimeMillis()
        )
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        assertEquals("有声小说", vm.keyword.first { it.isNotBlank() })
        assertEquals(1, vm.snapshotTracks.value?.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `scroll top request surfaces as tick`() = runTest(dispatcher) {
        val bus = cn.debubu.tingbili.core.ui.HomeScrollTopBus()
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, bus)
        assertEquals(0, vm.scrollTopTick.value)
        bus.requestScrollTop()
        assertEquals(1, vm.scrollTopTick.value)
    }

    @Test
    fun `SearchPagingSource loads first page via search`() = runTest {
        var firstPage: List<Track>? = null
        var firstTotal: Int? = null
        val source = SearchPagingSource(repo, "有声小说") { tracks, total ->
            firstPage = tracks; firstTotal = total
        }
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page<Int, Track>
        assertEquals(2, page.data.size)
        assertEquals("BV1xx", page.data[0].bvid)
        assertEquals(null, page.prevKey)
        assertEquals(2, page.nextKey)
        assertEquals(2, firstPage?.size)
        assertEquals(2, firstTotal)
        assertEquals("有声小说", fakeApi.lastSearchKeyword)
        // 纯搜索分页：不借用 related 扩展
        assertEquals(0, fakeApi.relatedCalls)
    }

    @Test
    fun `SearchPagingSource page 2 requests search page 2`() = runTest {
        fakeApi.page2Result = listOf(
            SearchItem(bvid = "BV5zz", title = "有声书精选", author = "up5", pic = "", duration = "2:00")
        )
        val source = SearchPagingSource(repo, "有声小说")
        source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        val result = source.load(PagingSource.LoadParams.Append(key = 2, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page<Int, Track>
        assertEquals(listOf("BV5zz"), page.data.map { it.bvid })
        assertEquals(1, page.prevKey)
        assertEquals(3, page.nextKey)
        assertTrue(2 in fakeApi.searchPageCalls)
        // 纯搜索分页：不再借用 related 扩展
        assertEquals(0, fakeApi.relatedCalls)
    }

    @Test
    fun `SearchPagingSource ends when search returns nothing`() = runTest {
        val source = SearchPagingSource(repo, "有声小说")
        source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        val result = source.load(PagingSource.LoadParams.Append(key = 2, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page<Int, Track>
        assertEquals(0, page.data.size)
        assertEquals(null, page.nextKey)
    }

    @Test
    fun `SearchPagingSource dedups bvids across pages but keeps paging`() = runTest {
        // 第 2 页把第 1 页已展示的 BV 原样推回来：去重后页 2 为空，但仍保留 nextKey 继续往后翻
        fakeApi.page2Result = listOf(
            SearchItem(bvid = "BV1xx", title = "音乐测试", author = "up1", pic = "", duration = "3:00"),
        )
        val source = SearchPagingSource(repo, "有声小说")
        source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        val result = source.load(PagingSource.LoadParams.Append(key = 2, loadSize = 20, placeholdersEnabled = false))
        val page = result as PagingSource.LoadResult.Page<Int, Track>
        assertEquals(0, page.data.size)
        assertEquals(3, page.nextKey)
    }

    @Test
    fun `SearchPagingSource error propagates`() = runTest {
        val errorApi = object : BiliApi {
            override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = throw RuntimeException("network error")
            override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
            override suspend fun related(bvid: String): RelatedDto = RelatedDto()
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int, qn: Int?, guest: Boolean): PlayUrlDto = PlayUrlDto(code = 0)
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
            override suspend fun favFoldersAll(upMid: Long): cn.debubu.tingbili.data.bilibili.dto.FavFolderDto = cn.debubu.tingbili.data.bilibili.dto.FavFolderDto()
            override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): cn.debubu.tingbili.data.bilibili.dto.FavDetailDto = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto()
            override suspend fun toview(pn: Int, ps: Int): cn.debubu.tingbili.data.bilibili.dto.ToviewDto = cn.debubu.tingbili.data.bilibili.dto.ToviewDto()
            override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto = cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto()
        }
        val errRepo = BiliRepository(errorApi)
        val source = SearchPagingSource(errRepo, "有声小说")
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `showBottomSheet expands BV via view`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        vm.showBottomSheetForBv("BV1xx")
        advanceUntilIdle()
        assertTrue(vm.showBottomSheet.first())
        val tracks = vm.expandedTracks.first()
        assertNotNull(tracks)
        assertEquals(2, tracks!!.size)
        assertEquals(1001L, tracks[0].cid)
    }

    @Test
    fun `getRefreshKey returns correctly`() = runTest {
        val source = SearchPagingSource(repo, "有声小说")
        val state = PagingState<Int, Track>(
            pages = listOf(
                PagingSource.LoadResult.Page(data = listOf(Track("BV1", 1, "t", "a", "", 0, null)), prevKey = null, nextKey = 2)
            ),
            anchorPosition = 0,
            config = androidx.paging.PagingConfig(20),
            leadingPlaceholderCount = 0
        )
        // anchor at 0, closest page prevKey null nextKey 2 => refreshKey 1
        assertEquals(1, source.getRefreshKey(state))
    }

    @Test
    fun `snapshot loads at startup when cached`() = runTest(dispatcher) {
        val tracks = listOf(Track("BV1xx", 1001, "T1", "a", "", 60000, null))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        snapshotDao.entity = HomeSnapshotEntity(
            keywordsJson = json.encodeToString(listOf("有声小说")),
            tracksJson = json.encodeToString(tracks),
            updatedAt = System.currentTimeMillis()
        )
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        // init 在 Dispatchers.IO 里读快照；先等 keyword 初始化完成，再断言快照
        vm.keyword.first { it.isNotBlank() }
        assertEquals(1, vm.snapshotTracks.value?.size)
        assertEquals("BV1xx", vm.snapshotTracks.value!![0].bvid)
    }

    @Test
    fun `first page saves snapshot`() = runTest {
        var saved: List<Track>? = null
        val source = SearchPagingSource(repo, "有声小说") { tracks, _ -> saved = tracks }
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Page)
        assertNotNull(saved)
        assertEquals(2, saved!!.size)
    }

    @Test
    fun `snapshot empty when no cache`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        advanceUntilIdle()
        assertEquals(null, vm.snapshotTracks.first())
    }

    @Test
    fun `no cache resolves a keyword and starts paging`() = runTest(dispatcher) {
        prefs.clearSearchHistory()
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        assertEquals("有声小说", vm.keyword.first { it.isNotBlank() })
        vm.viewModelScope.cancel()
    }

    @Test
    fun `stale snapshot from another keyword is ignored`() = runTest(dispatcher) {
        prefs.clearSearchHistory()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        snapshotDao.entity = HomeSnapshotEntity(
            keywordsJson = json.encodeToString(listOf("别的词")),
            tracksJson = json.encodeToString(listOf(Track("BV1xx", 1001, "T1", "a", "", 60000, null))),
            updatedAt = System.currentTimeMillis()
        )
        val vm = HomeViewModel(repo, prefs, player, snapshotDao, cn.debubu.tingbili.core.ui.HomeScrollTopBus())
        assertEquals("有声小说", vm.keyword.first { it.isNotBlank() })
        assertEquals(null, vm.snapshotTracks.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `SearchPagingSource maps 412 to rate-limited Error`() = runTest {
        val limitedApi = FakeBiliApi()
        val limitedRepo = BiliRepository(object : BiliApi {
            override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto =
                SearchDto(code = -412, message = "请求被拦截")
            override suspend fun view(bvid: String): ViewDto = limitedApi.view(bvid)
            override suspend fun related(bvid: String): RelatedDto = RelatedDto()
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int, qn: Int?, guest: Boolean): PlayUrlDto = limitedApi.playUrl(bvid, cid, fnval)
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = limitedApi.subtitle(bvid, cid)
            override suspend fun favFoldersAll(upMid: Long): cn.debubu.tingbili.data.bilibili.dto.FavFolderDto = cn.debubu.tingbili.data.bilibili.dto.FavFolderDto()
            override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): cn.debubu.tingbili.data.bilibili.dto.FavDetailDto = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto()
            override suspend fun toview(pn: Int, ps: Int): cn.debubu.tingbili.data.bilibili.dto.ToviewDto = cn.debubu.tingbili.data.bilibili.dto.ToviewDto()
            override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto = cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto()
        })
        val source = SearchPagingSource(limitedRepo, "有声小说")
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Error)
        val err = (result as PagingSource.LoadResult.Error).throwable
        assertTrue(err is cn.debubu.tingbili.data.bilibili.BiliRateLimitedException)
        assertTrue((err.message ?: "").contains("412"))
    }
}

// helper extension for preferencesDataStoreFile in test
private fun android.content.Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
