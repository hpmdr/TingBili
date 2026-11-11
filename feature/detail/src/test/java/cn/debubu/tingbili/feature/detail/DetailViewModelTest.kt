package cn.debubu.tingbili.feature.detail

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerHandle
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import cn.debubu.tingbili.data.bilibili.dto.ViewPage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.lifecycle.SavedStateHandle
import cn.debubu.tingbili.feature.detail.DetailViewModel

/**
 * 详情页状态机的最小回归网（该模块此前零测试，见 REVIEW_REPORT I-03）：
 * 加载成功把分P映射成 Track、失败暴露可重试错误、失败后可重试恢复。
 * fake 脚手架与 feature:history 的单测同源（PlayerManager + BiliRepository + 仓储 fake）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

        class FakePlayerHandle : PlayerHandle {
            var mediaItems: List<MediaItem> = emptyList()
            var startIndex: Int = -1
            var seekToPos: Long? = null
            var playCalled = false
            var lastTracks: List<Track> = emptyList()
            var lastIndex: Int = -1
            override var repeatMode: Int = Player.REPEAT_MODE_OFF
            override val currentMediaItemIndex: Int get() = startIndex.coerceAtLeast(0)
            private var _pos: Long = 0L
            override val currentPosition: Long get() = _pos
            override val duration: Long get() = 100_000L
            override val isPlaying: Boolean get() = true
            fun setPos(v: Long) { _pos = v }
    
            override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
                mediaItems = items; this.startIndex = startIndex; _pos = startPositionMs
            }
            override fun replaceMediaItem(index: Int, item: MediaItem) { if (index in mediaItems.indices) mediaItems = mediaItems.toMutableList().also { it[index] = item } }
    
            override val mediaItemCount: Int get() = mediaItems.size
            override fun addMediaItem(index: Int, item: MediaItem) {
                mediaItems = mediaItems.toMutableList().also { it.add(index.coerceIn(0, it.size), item) }
            }
            override fun removeMediaItem(index: Int) {
                mediaItems = mediaItems.toMutableList().also { if (index in it.indices) it.removeAt(index) }
            }
            override fun prepare() {}
            override fun play() { playCalled = true }
            override fun pause() {}
            override fun seekTo(positionMs: Long) { seekToPos = positionMs; _pos = positionMs }
            override fun setPlaybackSpeed(speed: Float) {}
            override fun addListener(listener: Player.Listener) {}
            override fun release() {}
        }

        // 同一个 fake 同时实现 DAO 与仓储：VM 用仓储，PlayerManager 用 DAO，测试里共享状态
    class FakeHistoryDao : HistoryDao, cn.debubu.tingbili.core.data.repository.HistoryRepository {
            private val flow = MutableStateFlow<List<HistoryEntity>>(emptyList())
            private val map = mutableMapOf<String, HistoryEntity>()
            private fun key(e: HistoryEntity) = "${e.sourceType}:${e.sourceId}"
        override suspend fun insertAll(rows: List<HistoryEntity>) {}
            override suspend fun save(e: HistoryEntity) {
                map[key(e)] = e
                flow.value = map.values.sortedByDescending { it.updatedAt }
            }
            override suspend fun getSource(sourceType: Int, sourceId: String): HistoryEntity? =
                map["$sourceType:$sourceId"]
            override suspend fun getLatestForTrack(bvid: String, cid: Long): HistoryEntity? =
                map.values.filter { it.bvid == bvid && it.cid == cid }.maxByOrNull { it.updatedAt }
            override suspend fun getAll(): List<HistoryEntity> = map.values.toList()
            override fun observeAll(): Flow<List<HistoryEntity>> = flow
            override fun observeTotalListenMs(): Flow<Long> = flowOf(0L)
            override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flow.map { list -> list.filter { it.bvid == bvid } }
            override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flow.map { list -> list.filter { it.bvid in bvids } }
            override fun observeTodayCount(todayStart: Long): Flow<Int> = flow.map { list -> list.count { it.updatedAt >= todayStart } }
            override suspend fun deleteSource(sourceType: Int, sourceId: String) {
                map.remove("$sourceType:$sourceId")
                flow.value = map.values.sortedByDescending { it.updatedAt }
            }
            override suspend fun clearAll() {
                map.clear()
                flow.value = emptyList()
            }
        }

        class FakeFavoritesRepository : cn.debubu.tingbili.core.data.repository.FavoritesRepository {
            val folders = mutableMapOf<Long, cn.debubu.tingbili.core.data.db.FavoriteFolderEntity>()
            val tracks = mutableMapOf<Long, List<cn.debubu.tingbili.core.data.db.FavoriteTrackEntity>>()
    
            override fun observeFolderSummaries(): Flow<List<cn.debubu.tingbili.core.data.db.FavoriteFolderSummary>> = flowOf(emptyList())
            override fun observeFolders(): Flow<List<cn.debubu.tingbili.core.data.db.FavoriteFolderEntity>> = flowOf(folders.values.toList())
            override fun observeFolder(id: Long): Flow<cn.debubu.tingbili.core.data.db.FavoriteFolderEntity?> = flowOf(folders[id])
            override suspend fun getFolder(id: Long): cn.debubu.tingbili.core.data.db.FavoriteFolderEntity? = folders[id]
            override fun observeTracks(folderId: Long): Flow<List<cn.debubu.tingbili.core.data.db.FavoriteTrackEntity>> = flowOf(tracks[folderId].orEmpty())
            override suspend fun getTracks(folderId: Long): List<cn.debubu.tingbili.core.data.db.FavoriteTrackEntity> = tracks[folderId].orEmpty()
            override fun observeBvFavorites(): Flow<List<cn.debubu.tingbili.core.data.db.BvFavoriteEntity>> = flowOf(emptyList())
            override fun observeBvFavorite(bvid: String): Flow<cn.debubu.tingbili.core.data.db.BvFavoriteEntity?> = flowOf(null)
            override fun observeBvPages(bvid: String): Flow<List<cn.debubu.tingbili.core.data.db.BvFavoritePageEntity>> = flowOf(emptyList())
            override fun observeFolderIdsContaining(bvid: String, cid: Long): Flow<Set<Long>> = flowOf(emptySet())
            override fun observeTrackInFolder(folderId: Long, bvid: String, cid: Long): Flow<Boolean> = flowOf(false)
            override fun observeIsBvFavorited(bvid: String): Flow<Boolean> = flowOf(false)
            override suspend fun ensureDefaultFolder(): Long = 1L
            override suspend fun createFolder(name: String): Long = 2L
            override suspend fun renameFolder(id: Long, name: String) {}
            override suspend fun deleteFolder(id: Long): Boolean = true
            override suspend fun reorderFolders(orderedIds: List<Long>) {}
            override suspend fun addTracksToFolders(
                folderIds: Set<Long>,
                tracks: List<Track>,
            ): cn.debubu.tingbili.core.data.model.AddTracksResult =
                cn.debubu.tingbili.core.data.model.AddTracksResult(tracks.size, 0)
            override suspend fun removeTrack(folderId: Long, bvid: String, cid: Long) {}
            override suspend fun toggleDefaultTrack(track: Track): Boolean = true
            override suspend fun toggleTrackInFolder(folderId: Long, track: Track): Boolean = true
            override suspend fun addBvFavorite(snapshot: cn.debubu.tingbili.core.data.model.BvFavoriteSnapshot) {}
            override suspend fun removeBvFavorite(bvid: String) {}
            override suspend fun refreshBvFavorite(snapshot: cn.debubu.tingbili.core.data.model.BvFavoriteSnapshot) {}
            override suspend fun hasAnyFavorite(): Boolean = false
        }

        class FakeBiliApi : BiliApi {
            var viewResult: ViewDto = ViewDto(
                code = 0,
                data = ViewData(
                    bvid = "BV1",
                    title = "Test Video",
                    pic = "https://example.com/cover.jpg",
                    owner = cn.debubu.tingbili.data.bilibili.dto.Owner(name = "author"),
                    pages = listOf(
                        ViewPage(cid = 1, page = 1, part = "P1", duration = 100),
                        ViewPage(cid = 2, page = 2, part = "P2", duration = 200)
                    ),
                    duration = 300
                )
            )
            override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto(code = 0)
            var viewCalls = 0
            override suspend fun view(bvid: String): ViewDto {
                viewCalls++
                return viewResult
            }
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int, qn: Int?, guest: Boolean): PlayUrlDto {
                return PlayUrlDto(
                    code = 0,
                    message = "",
                    data = cn.debubu.tingbili.data.bilibili.dto.PlayUrlData(
                        dash = cn.debubu.tingbili.data.bilibili.dto.DashData(
                            audio = listOf(cn.debubu.tingbili.data.bilibili.dto.DashAudio(baseUrl = "http://example.com/audio.mp3"))
                        )
                    )
                )
            }
            override suspend fun related(bvid: String): RelatedDto = RelatedDto()
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
            override suspend fun favFoldersAll(upMid: Long) =
                cn.debubu.tingbili.data.bilibili.dto.FavFolderDto(code = 0)
            override suspend fun favMedias(
                mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String
            ) = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto(code = 0)
            override suspend fun toview(pn: Int, ps: Int) =
                cn.debubu.tingbili.data.bilibili.dto.ToviewDto(code = 0)
            override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long) =
                cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto(code = 0)
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

    private lateinit var fakePlayer: FakePlayerHandle
    private lateinit var fakeDao: FakeHistoryDao
    private lateinit var fakeApi: FakeBiliApi
    private lateinit var fakeFavorites: FakeFavoritesRepository
    private lateinit var repo: BiliRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var playerManager: PlayerManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fakePlayer = FakePlayerHandle()
        fakeDao = FakeHistoryDao()
        fakeApi = FakeBiliApi()
        fakeFavorites = FakeFavoritesRepository()
        repo = BiliRepository(fakeApi)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_detail_prefs_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
        val resolvers = cn.debubu.tingbili.data.bilibili.BiliMediaResolvers(repo, prefs)
        playerManager = PlayerManager(
            fakePlayer, fakeDao, prefs, resolvers, resolvers, resolvers,
            FakePlayUrlCacheDao(), FakePlayQueueDao(), context,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        playerManager.release()
    }

    private fun vm(bvid: String = "BV1") =
        DetailViewModel(SavedStateHandle(mapOf("bvid" to bvid)), repo, playerManager, fakeFavorites, fakeDao)

    @Test
    fun `load success exposes video and tracks`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("期望 Success，实际 $state", state is DetailUiState.Success)
        val success = state as DetailUiState.Success
        assertEquals("Test Video", success.video.title)
        assertEquals(2, success.tracks.size)
        // toTracks 会把 pageCount/pageIndex 带上，供详情页渲染「P1 · 章节名」
        assertEquals(2, success.tracks[0].pageCount)
        assertEquals(1, success.tracks[0].pageIndex)
        assertEquals(1L, success.tracks[0].cid)
        assertEquals(2L, success.tracks[1].cid)
        assertEquals(1, fakeApi.viewCalls)
    }

    @Test
    fun `load error exposes retryable message`() = runTest(dispatcher) {
        fakeApi.viewResult = ViewDto(code = -404, message = "视频不存在")
        val vm = vm(bvid = "BV_err")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("期望 Error，实际 $state", state is DetailUiState.Error)
        assertEquals("视频不存在", (state as DetailUiState.Error).message)
    }

    @Test
    fun `load retries after error and recovers`() = runTest(dispatcher) {
        fakeApi.viewResult = ViewDto(code = -500, message = "服务器开小差")
        val vm = vm(bvid = "BV_retry")
        advanceUntilIdle()
        assertTrue(vm.state.value is DetailUiState.Error)

        fakeApi.viewResult = ViewDto(
            code = 0,
            data = ViewData(
                bvid = "BV_retry",
                title = "重试成功",
                pic = "https://example.com/c.jpg",
                owner = cn.debubu.tingbili.data.bilibili.dto.Owner(name = "UP"),
                pages = listOf(ViewPage(cid = 9, page = 1, part = "P1", duration = 60)),
                duration = 60,
            ),
        )
        vm.load()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("重试后期望 Success，实际 $state", state is DetailUiState.Success)
        assertEquals("重试成功", (state as DetailUiState.Success).video.title)
        assertEquals(1, successTracksCount(state))
    }

    private fun successTracksCount(s: DetailUiState.Success): Int = s.tracks.size
}
