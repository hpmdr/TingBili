package cn.debubu.tingbili.feature.history

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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {

    @get:Rule
    val instantTask = InstantTaskExecutorRule()

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

    class FakePlayUrlCacheDao : cn.debubu.tingbili.core.data.db.PlayUrlCacheDao {
        val map = mutableMapOf<String, cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>()
        override suspend fun getAll(): List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity> = map.values.toList()
        override suspend fun putAll(rows: List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>) {
            rows.forEach { map[it.key] = it }
        }
        override suspend fun pruneExpired(cutoffMs: Long) {
            map.entries.removeIf { it.value.fetchedAtMs < cutoffMs }
        }
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
        private val flow = MutableStateFlow<List<HistoryEntity>>(emptyList())
        private val map = mutableMapOf<Pair<String, Long>, HistoryEntity>()
        override suspend fun save(e: HistoryEntity) {
            map[e.bvid to e.cid] = e
            flow.value = map.values.sortedByDescending { it.updatedAt }
        }
        override suspend fun get(bvid: String, cid: Long): HistoryEntity? = map[bvid to cid]
        override suspend fun getAll(): List<HistoryEntity> = map.values.toList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flow
        override fun observeTotalListenMs(): Flow<Long> = flowOf(0L)
        override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flow.map { list -> list.filter { it.bvid == bvid } }
        override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flow.map { list -> list.filter { it.bvid in bvids } }
        override fun observeTodayCount(todayStart: Long): Flow<Int> = flow.map { list -> list.count { it.updatedAt >= todayStart } }
        override suspend fun delete(bvid: String, cid: Long) {
            map.remove(bvid to cid)
            flow.value = map.values.sortedByDescending { it.updatedAt }
        }
        override suspend fun clearAll() {
            map.clear()
            flow.value = emptyList()
        }
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
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int, qn: Int?): PlayUrlDto {
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
        override suspend fun favFolders(pn: Int, ps: Int, upMid: Long) =
            cn.debubu.tingbili.data.bilibili.dto.FavFolderDto(code = 0)
        override suspend fun favMedias(
            mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String
        ) = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto(code = 0)
        override suspend fun toview(pn: Int, ps: Int) =
            cn.debubu.tingbili.data.bilibili.dto.ToviewDto(code = 0)
        override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long) =
            cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto(code = 0)
    }

    private lateinit var fakePlayer: FakePlayerHandle
    private lateinit var fakeDao: FakeHistoryDao
    private lateinit var fakeApi: FakeBiliApi
    private lateinit var repo: BiliRepository
    private lateinit var prefs: PreferencesRepository
    private lateinit var playerManager: PlayerManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fakePlayer = FakePlayerHandle()
        fakeDao = FakeHistoryDao()
        fakeApi = FakeBiliApi()
        repo = BiliRepository(fakeApi)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_history_prefs_${System.nanoTime()}")
        if (file.exists()) file.delete()
        // DataStore 也跑在测试调度器上，读写才受虚拟时间控制（否则断言可能读到默认值）
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
        val resolvers = cn.debubu.tingbili.data.bilibili.BiliMediaResolvers(repo, prefs)
        playerManager = PlayerManager(fakePlayer, fakeDao, prefs, resolvers, resolvers, resolvers, FakePlayUrlCacheDao(), FakePlayQueueDao(), context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        playerManager.release()
    }

    @Test
    fun `resume seeks to saved position`() = runTest(dispatcher) {
        try {
            fakeDao.save(HistoryEntity("BV1", 1, 12345, System.currentTimeMillis()))
            val vm = HistoryViewModel(fakeDao, playerManager, repo)
            // brief's History(bvid,cid) maps to HistoryEntity
            vm.resume(HistoryEntity("BV1", 1, 12345, System.currentTimeMillis()))
            runCurrent()
            // seekToPos 是 Long?：字面量必须带 L，否则只能走 assertEquals(Object,Object)，
            // 变成 Integer 与 Long 比较，必然不等。
            assertEquals(12345L, fakePlayer.seekToPos)
            // also verify play was called with correct index (cid 1 -> idx 0)
            assertEquals(0, fakePlayer.startIndex)
        } finally {
            // play() 启动的 while(true){delay()} 节流任务必须先停掉：runTest 收尾自己会 advanceUntilIdle，
            // 留着它们就永不返回（表现为 100% CPU 卡死）。放 finally 保证断言失败时也不会挂住。
            playerManager.release()
        }
    }

    @Test
    fun `video info loads lazily on prefetch, not on history emit`() = runTest(dispatcher) {
        val vm = HistoryViewModel(fakeDao, playerManager, repo)
        fakeDao.save(HistoryEntity("BV1", 1, 1000, 1L))
        fakeDao.save(HistoryEntity("BV2", 2, 2000, 2L))
        runCurrent()
        // 进页只收历史：不自动发 view，全量拉的风暴根源
        assertEquals(0, fakeApi.viewCalls)
        assertTrue(vm.videoInfo.value.isEmpty())
        vm.prefetch("BV1")
        runCurrent()
        assertEquals(1, fakeApi.viewCalls)
        assertEquals(listOf("BV1"), vm.videoInfo.value.keys.sorted())
        // 同一 BV 重复 prefetch 去重，不再发请求
        vm.prefetch("BV1")
        vm.prefetch("BV1")
        runCurrent()
        assertEquals(1, fakeApi.viewCalls)
    }

    @Test
    fun `history flow emits saved entities`() = runTest(dispatcher) {
        val vm = HistoryViewModel(fakeDao, playerManager, repo)
        fakeDao.save(HistoryEntity("BV1", 1, 1000, 1L))
        fakeDao.save(HistoryEntity("BV2", 2, 2000, 2L))
        runCurrent()
        val list = vm.history.first()
        // observeAll sorts by updatedAt DESC, so BV2 first
        assertEquals(2, list.size)
        assertEquals("BV2", list[0].bvid)
    }

    @Test
    fun `resume falls back to index 0 when cid not found`() = runTest(dispatcher) {
        try {
            // dao contains cid 999 which is not in view pages (1,2)
            fakeDao.save(HistoryEntity("BV1", 999, 54321, System.currentTimeMillis()))
            fakeApi.viewResult = ViewDto(
                code = 0,
                data = ViewData(
                    bvid = "BV1", title = "T", pic = "",
                    owner = cn.debubu.tingbili.data.bilibili.dto.Owner(name = "a"),
                    pages = listOf(ViewPage(cid = 1, page = 1, part = "P1", duration = 60)),
                    duration = 60
                )
            )
            val vm = HistoryViewModel(fakeDao, playerManager, repo)
            vm.resume(HistoryEntity("BV1", 999, 54321, System.currentTimeMillis()))
            runCurrent()
            // should play at idx 0 and seek to 54321
            assertEquals(0, fakePlayer.startIndex)
            assertEquals(54321L, fakePlayer.seekToPos)
        } finally {
            playerManager.release()
        }
    }

    @Test
    fun `resume does nothing when view returns error`() = runTest(dispatcher) {
        try {
            fakeApi.viewResult = ViewDto(code = -404, message = "not found", data = null)
            val vm = HistoryViewModel(fakeDao, playerManager, repo)
            vm.resume(HistoryEntity("BV_NOT_EXIST", 1, 9999, System.currentTimeMillis()))
            runCurrent()
            assertNull(fakePlayer.seekToPos)
        } finally {
            playerManager.release()
        }
    }

    @Test
    fun `resume chooses correct index for second part`() = runTest(dispatcher) {
        try {
            fakeDao.save(HistoryEntity("BV1", 2, 7777, System.currentTimeMillis()))
            val vm = HistoryViewModel(fakeDao, playerManager, repo)
            vm.resume(HistoryEntity("BV1", 2, 7777, System.currentTimeMillis()))
            runCurrent()
            assertEquals(1, fakePlayer.startIndex)
            assertEquals(7777L, fakePlayer.seekToPos)
        } finally {
            playerManager.release()
        }
    }
}

private fun Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
