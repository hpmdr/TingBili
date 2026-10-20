package cn.debubu.tingbili.feature.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistSummary
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
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
        var viewResult: ViewDto = ViewDto(
            code = 0, data = ViewData(
                bvid = "BV1xx", title = "My Video", pic = "https://cover",
                owner = cn.debubu.tingbili.data.bilibili.dto.Owner(name = "author1"),
                pages = listOf(ViewPage(cid = 1001, page = 1, part = "P1", duration = 60), ViewPage(cid = 1002, page = 2, part = "P2", duration = 120))
            )
        )
        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto {
            lastSearchKeyword = keyword
            // simulate paging: page 1 returns searchResult, page 2+ empty
            val data = if (page == 1) SearchData(result = searchResult, page = page, numResults = searchResult.size, numPages = 1) else SearchData(result = emptyList(), page = page)
            return SearchDto(code = 0, data = data)
        }
        override suspend fun view(bvid: String): ViewDto = viewResult
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0, data = cn.debubu.tingbili.data.bilibili.dto.PlayUrlData(dash = cn.debubu.tingbili.data.bilibili.dto.DashData(audio = listOf(cn.debubu.tingbili.data.bilibili.dto.DashAudio(baseUrl = "https://audio.example.com/a.mp3")))))
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
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

    class FakeHistoryDao : HistoryDao {
        override suspend fun save(e: HistoryEntity) {}
        override suspend fun get(bvid: String, cid: Long): HistoryEntity? = null
        override suspend fun getAll(): List<HistoryEntity> = emptyList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTotalListenMs(): Flow<Long> = flowOf(0L)
        override suspend fun delete(bvid: String, cid: Long) {}
        override suspend fun clearAll() {}
    }

    class FakePlaylistDao : PlaylistDao {
        private var idSeq = 1L
        private val playlists = mutableListOf<PlaylistEntity>()
        private val tracks = mutableListOf<PlaylistTrackEntity>()
        override suspend fun insert(p: PlaylistEntity): Long { val id = idSeq++; playlists.add(p.copy(id = id)); return id }
        override suspend fun addTrack(t: PlaylistTrackEntity) { tracks.add(t) }
        override suspend fun getTracks(id: Long): List<PlaylistTrackEntity> = tracks.filter { it.playlistId == id }
        override fun observePlaylists(): Flow<List<PlaylistEntity>> = flowOf(playlists)
        override fun observePlaylistSummaries(): Flow<List<PlaylistSummary>> = flowOf(
            playlists.map { playlist ->
                val playlistTracks = tracks.filter { it.playlistId == playlist.id }
                PlaylistSummary(
                    playlist = playlist,
                    trackCount = playlistTracks.size,
                    totalDurationMs = playlistTracks.sumOf { it.durationMs }
                )
            }
        )
        override suspend fun getPlaylists(): List<PlaylistEntity> = playlists.toList()
        override suspend fun removeTrack(playlistId: Long, bvid: String, cid: Long) { tracks.removeIf { it.playlistId == playlistId && it.bvid == bvid && it.cid == cid } }
        override suspend fun deletePlaylist(id: Long) { playlists.removeIf { it.id == id } }
        override suspend fun clearTracks(playlistId: Long) { tracks.removeIf { it.playlistId == playlistId } }
        override suspend fun updateOrder(playlistId: Long, bvid: String, cid: Long, newOrder: Int) {
            val idx = tracks.indexOfFirst { it.playlistId == playlistId && it.bvid == bvid && it.cid == cid }
            if (idx >= 0) tracks[idx] = tracks[idx].copy(order = newOrder)
        }

        override suspend fun updateTrackMeta(playlistId: Long, bvid: String, cid: Long, videoTitle: String?, pageIndex: Int?) {
            val idx = tracks.indexOfFirst { it.playlistId == playlistId && it.bvid == bvid && it.cid == cid }
            if (idx >= 0) tracks[idx] = tracks[idx].copy(videoTitle = videoTitle, pageIndex = pageIndex)
        }
        override fun observePlaylist(id: Long): Flow<PlaylistEntity?> = flowOf(playlists.firstOrNull { it.id == id })
        override fun observeTracks(id: Long): Flow<List<PlaylistTrackEntity>> = flowOf(tracks.filter { it.playlistId == id }.sortedBy { it.order })
        override suspend fun updateName(playlistId: Long, name: String) {
            val idx = playlists.indexOfFirst { it.id == playlistId }
            if (idx >= 0) playlists[idx] = playlists[idx].copy(name = name)
        }
        override suspend fun getPlaylist(id: Long): PlaylistEntity? = playlists.firstOrNull { it.id == id }
        override suspend fun getPlaylistByBvid(bvid: String): PlaylistEntity? =
            playlists.firstOrNull { it.sourceBvid == bvid }
        override fun observePlaylistByBvid(bvid: String): Flow<PlaylistEntity?> =
            flowOf(playlists.firstOrNull { it.sourceBvid == bvid })
        override suspend fun updateCover(playlistId: Long, cover: String) {
            val idx = playlists.indexOfFirst { it.id == playlistId }
            if (idx >= 0) playlists[idx] = playlists[idx].copy(cover = cover)
        }
        fun addedCount() = tracks.size
    }

    class FakeHomeSnapshotDao : HomeSnapshotDao {
        var entity: HomeSnapshotEntity? = null
        override suspend fun get(): HomeSnapshotEntity? = entity
        override fun observe(): Flow<HomeSnapshotEntity?> = flowOf(entity)
        override suspend fun save(e: HomeSnapshotEntity) { entity = e }
        override suspend fun clear() { entity = null }
    }

    private lateinit var fakeApi: FakeBiliApi
    private lateinit var repo: BiliRepository
    private lateinit var player: PlayerManager
    private lateinit var playlistDao: FakePlaylistDao
    private lateinit var snapshotDao: FakeHomeSnapshotDao
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fakeApi = FakeBiliApi()
        repo = BiliRepository(fakeApi)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesRepository(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("test_prefs") })
        player = PlayerManager(FakePlayerHandle(), FakeHistoryDao(), prefs, repo, context)
        playlistDao = FakePlaylistDao()
        snapshotDao = FakeHomeSnapshotDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `home exposes recommended paging data`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        val pagingData = vm.pagingFlow.first()
        assertNotNull(pagingData)
    }

    @Test
    fun `shuffle emits new paging data`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        vm.pagingFlow.first()
        vm.shuffleKeywords()
        // 常驻流：刷新后必有新一份 PagingData（只断言换新，不断言随机顺序本身）
        assertNotNull(vm.pagingFlow.drop(1).first())
    }

    @Test
    fun `startPaging opens gate on snapshot cold start`() = runTest(dispatcher) {
        snapshotDao.entity = HomeSnapshotEntity(
            tracksJson = "[{\"bvid\":\"BV1\",\"cid\":1,\"title\":\"t\",\"author\":\"a\",\"cover\":\"\",\"durationMs\":1000,\"subtitleUrl\":null}]",
            updatedAt = System.currentTimeMillis()
        )
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        runCurrent()
        assertEquals(false, vm.pagingStarted.value)
        vm.startPaging()
        assertEquals(true, vm.pagingStarted.value)
    }

    @Test
    fun `HomePagingSource loads page`() = runTest {
        val source = HomePagingSource(repo)
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page<Int, Track>
        assertEquals(2, page.data.size)
        assertEquals("BV1xx", page.data[0].bvid)
        assertEquals(1, page.prevKey ?: 1) // prevKey null for page 1
        // nextKey should be 2 when data non-empty
        assertEquals(2, page.nextKey)
    }

    @Test
    fun `HomePagingSource uses recommended keyword`() = runTest {
        val source = HomePagingSource(repo)
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Page)
        assertTrue(HomePagingSource.DEFAULT_RECOMMEND_KEYWORDS.contains(fakeApi.lastSearchKeyword))
    }

    @Test
    fun `rotation restarts api page per keyword round`() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val api = object : BiliApi {
            override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto {
                calls.add(keyword to page)
                return SearchDto(code = 0, data = SearchData(result = fakeApi.searchResult, page = page, numResults = 2, numPages = 9))
            }
            override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
        }
        val kws = listOf("A", "B", "C", "D")
        val source = HomePagingSource(BiliRepository(api), kws)
        (1..6).forEach { pg ->
            val r = source.load(PagingSource.LoadParams.Refresh(key = pg, loadSize = 20, placeholdersEnabled = false))
            assertTrue(r is PagingSource.LoadResult.Page)
        }
        assertEquals(
            listOf("A" to 1, "B" to 1, "C" to 1, "D" to 1, "A" to 2, "B" to 2),
            calls
        )
    }

    @Test
    fun `HomePagingSource error propagates`() = runTest {
        val errorApi = object : BiliApi {
            override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = throw RuntimeException("network error")
            override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
        }
        val errRepo = BiliRepository(errorApi)
        val source = HomePagingSource(errRepo)
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `showBottomSheet expands BV via view`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        vm.showBottomSheetForBv("BV1xx")
        advanceUntilIdle()
        assertTrue(vm.showBottomSheet.first())
        val tracks = vm.expandedTracks.first()
        assertNotNull(tracks)
        assertEquals(2, tracks!!.size)
        assertEquals(1001L, tracks[0].cid)
    }

    @Test
    fun `addToPlaylist single and all`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        val track = Track(bvid = "BV1xx", cid = 1001, title = "T1", author = "a", cover = "", durationMs = 60000, subtitleUrl = null)
        vm.addToPlaylist(track)
        advanceUntilIdle()
        assertEquals(1, playlistDao.addedCount())
        vm.addToPlaylist(listOf(track, track.copy(cid = 1002)))
        advanceUntilIdle()
        assertEquals(3, playlistDao.addedCount())
    }

    @Test
    fun `getRefreshKey returns correctly`() = runTest {
        val source = HomePagingSource(repo)
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
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        advanceUntilIdle()
        assertEquals(1, vm.snapshotTracks.first()?.size)
        assertEquals("BV1xx", vm.snapshotTracks.first()!![0].bvid)
    }

    @Test
    fun `first page saves snapshot`() = runTest {
        var saved: List<Track>? = null
        val source = HomePagingSource(repo) { tracks, _ -> saved = tracks }
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false))
        assertTrue(result is PagingSource.LoadResult.Page)
        assertNotNull(saved)
        assertEquals(2, saved!!.size)
    }

    @Test
    fun `snapshot empty when no cache`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        advanceUntilIdle()
        assertEquals(null, vm.snapshotTracks.first())
    }

    @Test
    fun `no cache auto starts paging`() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        advanceUntilIdle()
        assertEquals(true, vm.pagingStarted.first())
    }

    @Test
    fun `cached snapshot blocks auto paging until manual refresh`() = runTest(dispatcher) {
        val tracks = listOf(Track("BV1xx", 1001, "T1", "a", "", 60000, null))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        snapshotDao.entity = HomeSnapshotEntity(
            keywordsJson = json.encodeToString(listOf("有声小说")),
            tracksJson = json.encodeToString(tracks),
            updatedAt = System.currentTimeMillis()
        )
        val vm = HomeViewModel(repo, prefs, player, playlistDao, snapshotDao)
        advanceUntilIdle()
        assertEquals(false, vm.pagingStarted.first())
        assertEquals(1, vm.snapshotTracks.first()?.size)
        vm.shuffleKeywords()
        advanceUntilIdle()
        assertEquals(true, vm.pagingStarted.first())
    }
}

// helper extension for preferencesDataStoreFile in test
private fun android.content.Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
