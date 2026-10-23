package cn.debubu.tingbili.feature.player

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerHandle
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.SubtitleCache
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerViewModelTest {

    @get:Rule
    val instantTask = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    /** 字幕接口巨慢（10s）：复现“打开播放页闪一下未播放”的窗口 */
    class SlowSubtitleApi : BiliApi {
        var subtitleCalls = 0
        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto()
        override suspend fun view(bvid: String): ViewDto = ViewDto()
            override suspend fun related(bvid: String): RelatedDto = RelatedDto()
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto {
            subtitleCalls++
            delay(10_000)
            return SubtitleDto()
        }
        override suspend fun favFolders(pn: Int, ps: Int, upMid: Long): cn.debubu.tingbili.data.bilibili.dto.FavFolderDto = cn.debubu.tingbili.data.bilibili.dto.FavFolderDto()
        override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): cn.debubu.tingbili.data.bilibili.dto.FavDetailDto = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto()
        override suspend fun toview(pn: Int, ps: Int): cn.debubu.tingbili.data.bilibili.dto.ToviewDto = cn.debubu.tingbili.data.bilibili.dto.ToviewDto()
        override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto = cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto()
    }

    class FakePlayerHandle : PlayerHandle {
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override val currentMediaItemIndex: Int get() = 0
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override val isPlaying: Boolean get() = false
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {}
        override fun replaceMediaItem(index: Int, item: MediaItem) {}
        override fun addMediaItem(index: Int, item: MediaItem) {}
        override fun removeMediaItem(index: Int) {}
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
        override suspend fun save(e: HistoryEntity) {}
        override suspend fun get(bvid: String, cid: Long): HistoryEntity? = null
        override suspend fun getAll(): List<HistoryEntity> = emptyList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTotalListenMs(): Flow<Long> = flowOf(0L)
        override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTodayCount(todayStart: Long): Flow<Int> = flowOf(0)
        override suspend fun delete(bvid: String, cid: Long) {}
        override suspend fun clearAll() {}
    }

    class FakeAuthStore(var session: BiliSession? = null) : AuthStore {
        override fun load(): BiliSession? = session
        override fun save(session: BiliSession?) { this.session = session }
    }

    private lateinit var api: SlowSubtitleApi
    private lateinit var repo: BiliRepository
    private lateinit var player: PlayerManager
    private lateinit var fakePlayQueue: FakePlayQueueDao
    private lateinit var prefs: PreferencesRepository
    private lateinit var authStore: FakeAuthStore
    private lateinit var auth: AuthRepository
    private val testJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = SlowSubtitleApi()
        repo = BiliRepository(api)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesRepository(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = { context.preferencesDataStoreFile("player_test_prefs") }
            )
        )
        val resolvers = cn.debubu.tingbili.data.bilibili.BiliMediaResolvers(repo)
        fakePlayQueue = FakePlayQueueDao()
        player = PlayerManager(FakePlayerHandle(), FakeHistoryDao(), prefs, resolvers, resolvers, resolvers, FakePlayUrlCacheDao(), fakePlayQueue, context)
        authStore = FakeAuthStore()
        auth = AuthRepository(testJson, authStore)
    }

    /** 登录态在 AuthRepository 构造时从 store 快照：先设 session 再建 auth */
    private fun loginAndRebuildAuth() {
        authStore.session = BiliSession(sessdata = "s", biliJct = "j", uid = 1L)
        auth = AuthRepository(testJson, authStore)
    }

    private fun demoTrack() = Track(
        bvid = "BV1xx", cid = 1001, title = "P1 · 测试", author = "up",
        cover = "", durationMs = 60_000, subtitleUrl = null,
        pageCount = 1, videoTitle = "测试书", pageIndex = 1
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState shows track without waiting for slow subtitles`() = runTest(dispatcher) {
        loginAndRebuildAuth()
        val track = demoTrack()
        // 用“冷启动恢复” seeding 当前曲目：只跑有限任务，不启动无限轮询，
        // runTest 收尾不会卡死（见 CONVENTIONS 坑 1）。
        fakePlayQueue.snapshot = cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity(tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(listOf(track)), currentIndex = 0, sourceTitle = "测试书")
        // 恢复与预热都是挂起等真线程 IO，直接调、等它完事，不确定性到此为止
        player.restoreLastPlayback()
        prefs.stepSec.first()
        assertEquals("BV1xx", player.state.value.currentTrack?.bvid)
        val vm = PlayerViewModel(player, SubtitleCache(repo, FakeSubtitleDiskDao()), auth, prefs, repo, dispatcher)
        val collectJob = launch { vm.uiState.collect {} }
        try {
            runCurrent()
            advanceTimeBy(100) // 字幕 delay(10s) 还没回来
            val s = vm.uiState.value
            // 回归断言：没修之前这里 track=null，顶栏闪“未播放”
            assertEquals("BV1xx", s.track?.bvid)
            assertEquals("测试书", playerHeaderTitle(s.track))
            assertEquals(SubtitleLoadState.Loading, s.subtitleLoadState)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `logged out shows NoAuth without network`() = runTest(dispatcher) {
        val track = demoTrack()
        fakePlayQueue.snapshot = cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity(tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(listOf(track)), currentIndex = 0, sourceTitle = "测试书")
        player.restoreLastPlayback()
        prefs.stepSec.first()
        val vm = PlayerViewModel(player, SubtitleCache(repo, FakeSubtitleDiskDao()), auth, prefs, repo, dispatcher)
        val collectJob = launch { vm.uiState.collect {} }
        try {
            runCurrent()
            advanceTimeBy(100)
            val s = vm.uiState.value
            assertEquals("BV1xx", s.track?.bvid)
            assertEquals(SubtitleLoadState.NoAuth, s.subtitleLoadState)
            assertEquals(0, api.subtitleCalls)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `subtitle error then retry recovers`() = runTest(dispatcher) {
        loginAndRebuildAuth()
        val failingApi = object : BiliApi {
            var calls = 0
            var fail = true
            override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto()
            override suspend fun view(bvid: String): ViewDto = ViewDto()
            override suspend fun related(bvid: String): RelatedDto = RelatedDto()
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto {
                calls++
                if (fail) throw RuntimeException("cdn 抽风")
                return SubtitleDto()
            }
            override suspend fun favFolders(pn: Int, ps: Int, upMid: Long): cn.debubu.tingbili.data.bilibili.dto.FavFolderDto = cn.debubu.tingbili.data.bilibili.dto.FavFolderDto()
            override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): cn.debubu.tingbili.data.bilibili.dto.FavDetailDto = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto()
            override suspend fun toview(pn: Int, ps: Int): cn.debubu.tingbili.data.bilibili.dto.ToviewDto = cn.debubu.tingbili.data.bilibili.dto.ToviewDto()
            override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto = cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto()
            fun callCount() = calls
        }
        val cache = SubtitleCache(BiliRepository(failingApi), FakeSubtitleDiskDao())
        val track = demoTrack()
        fakePlayQueue.snapshot = cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity(tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(listOf(track)), currentIndex = 0, sourceTitle = "测试书")
        player.restoreLastPlayback()
        prefs.stepSec.first()
        val vm = PlayerViewModel(player, cache, auth, prefs, repo, dispatcher)
        val collectJob = launch { vm.uiState.collect {} }
        try {
            runCurrent()
            advanceTimeBy(100)
            assertEquals(SubtitleLoadState.Error, vm.uiState.value.subtitleLoadState)
            failingApi.fail = false
            vm.retrySubtitles()
            runCurrent()
            advanceTimeBy(100)
            assertEquals(SubtitleLoadState.Empty, vm.uiState.value.subtitleLoadState)
            assertEquals(2, failingApi.callCount())
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `fresh disk snapshot shows Ready with zero network`() = runTest(dispatcher) {
        loginAndRebuildAuth()
        val track = demoTrack().copy(subtitleUrl = null)
        val disk = FakeSubtitleDiskDao()
        val seed = SubtitleCache(repo, disk)
        val tracks = listOf(cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack("zh-CN", "中文", "https://x/s.json", false))
        val lines = listOf(cn.debubu.tingbili.data.bilibili.model.LyricLine(1000L, "你好"))
        seed.store(track.bvid, track.cid, tracks, 0, lines, "中文", false)
        val callsBefore = api.subtitleCalls
        fakePlayQueue.snapshot = cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity(tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(listOf(track)), currentIndex = 0, sourceTitle = "测试书")
        player.restoreLastPlayback()
        val vm = PlayerViewModel(player, SubtitleCache(repo, disk), auth, prefs, repo, dispatcher)
        val collectJob = launch { vm.uiState.collect {} }
        try {
            runCurrent()
            advanceTimeBy(100)
            assertEquals(SubtitleLoadState.Ready, vm.uiState.value.subtitleLoadState)
            assertEquals(lines, vm.uiState.value.lyrics)
            assertEquals(callsBefore, api.subtitleCalls)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `refreshBv keeps old queue on view failure`() = runTest(dispatcher) {
        loginAndRebuildAuth()
        val track = demoTrack()
        fakePlayQueue.snapshot = cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity(tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(listOf(track)), currentIndex = 0, sourceTitle = "测试书")
        player.restoreLastPlayback()
        // SlowSubtitleApi.view 回空 data → getViewDetail 走 Error（真机对应“刷新失败”）
        val vm = PlayerViewModel(player, SubtitleCache(repo, FakeSubtitleDiskDao()), auth, prefs, repo, dispatcher)
        val collectJob = launch { vm.uiState.collect {} }
        try {
            runCurrent()
            vm.refreshBv()
            runCurrent()
            advanceTimeBy(200)
            assertEquals("刷新失败，已保留旧数据", vm.message.value)
            assertEquals(listOf(track), vm.uiState.value.queue)
            vm.consumeMessage()
            assertEquals(null, vm.message.value)
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `refreshBv works without uiState collectors`() = runTest(dispatcher) {
        // 回归：屏幕改收三层分流后 uiState 无生产订阅者，事件函数若读 uiState.value
        // 会拿到 track=null 的初值而静默 return（真机点刷新毫无反应）
        loginAndRebuildAuth()
        val track = demoTrack()
        fakePlayQueue.snapshot = cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity(tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(listOf(track)), currentIndex = 0, sourceTitle = "测试书")
        player.restoreLastPlayback()
        val vm = PlayerViewModel(player, SubtitleCache(repo, FakeSubtitleDiskDao()), auth, prefs, repo, dispatcher)
        // 故意不 collect vm.uiState：SlowSubtitleApi.view 回空 data → 走 Error 分支
        runCurrent()
        vm.refreshBv()
        runCurrent()
        advanceTimeBy(200)
        assertEquals("刷新失败，已保留旧数据", vm.message.value)
    }

    @Test
    fun `subtitle scale defaults persists and clamps`() = runTest(dispatcher) {
        assertEquals(1f, prefs.subtitleScale.first())
        prefs.setSubtitleScale(1.15f)
        assertEquals(1.15f, prefs.subtitleScale.first())
        prefs.setSubtitleScale(99f)
        assertEquals(1.3f, prefs.subtitleScale.first())
        prefs.setSubtitleScale(0f)
        assertEquals(0.85f, prefs.subtitleScale.first())
    }
}

private fun android.content.Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")

/** 字幕盘内存替身：空盘=老行为（纯内存），预存=冷启动快照 */
private class FakeSubtitleDiskDao(
    private val map: MutableMap<String, cn.debubu.tingbili.core.data.db.SubtitleDiskEntity> = mutableMapOf()
) : cn.debubu.tingbili.core.data.db.SubtitleDiskDao {
    override suspend fun get(key: String) = map[key]
    override suspend fun save(e: cn.debubu.tingbili.core.data.db.SubtitleDiskEntity) { map[e.cacheKey] = e }
    override suspend fun prune(keep: Int) {
        map.values.sortedByDescending { it.fetchedAt }.drop(keep).forEach { map.remove(it.cacheKey) }
    }
    override suspend fun clear() = map.clear()
    override suspend fun deleteByPrefix(prefix: String) { map.keys.filter { it.startsWith(prefix) }.forEach { map.remove(it) } }
    override suspend fun rowSizes(): List<cn.debubu.tingbili.core.data.db.SubtitleRowSize> = map.values.map { cn.debubu.tingbili.core.data.db.SubtitleRowSize(it.tracksJson, it.linesJson) }
}
