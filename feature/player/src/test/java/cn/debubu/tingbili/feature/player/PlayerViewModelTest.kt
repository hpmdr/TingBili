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
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto {
            subtitleCalls++
            delay(10_000)
            return SubtitleDto()
        }
    }

    class FakePlayerHandle : PlayerHandle {
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override val currentMediaItemIndex: Int get() = 0
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override val isPlaying: Boolean get() = false
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {}
        override fun replaceMediaItem(index: Int, item: MediaItem) {}
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

    class FakeAuthStore(var session: BiliSession? = null) : AuthStore {
        override fun load(): BiliSession? = session
        override fun save(session: BiliSession?) { this.session = session }
    }

    private lateinit var api: SlowSubtitleApi
    private lateinit var repo: BiliRepository
    private lateinit var player: PlayerManager
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
        player = PlayerManager(FakePlayerHandle(), FakeHistoryDao(), prefs, repo, context)
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
        prefs.setLastPlayback(listOf(track), 0, 0L, "测试书")
        // 恢复与预热都是挂起等真线程 IO，直接调、等它完事，不确定性到此为止
        player.restoreLastPlayback()
        prefs.stepSec.first()
        assertEquals("BV1xx", player.state.value.currentTrack?.bvid)
        val vm = PlayerViewModel(player, SubtitleCache(repo), auth, prefs)
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
        prefs.setLastPlayback(listOf(track), 0, 0L, "测试书")
        player.restoreLastPlayback()
        prefs.stepSec.first()
        val vm = PlayerViewModel(player, SubtitleCache(repo), auth, prefs)
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
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto {
                calls++
                if (fail) throw RuntimeException("cdn 抽风")
                return SubtitleDto()
            }
            fun callCount() = calls
        }
        val cache = SubtitleCache(BiliRepository(failingApi))
        val track = demoTrack()
        prefs.setLastPlayback(listOf(track), 0, 0L, "测试书")
        player.restoreLastPlayback()
        prefs.stepSec.first()
        val vm = PlayerViewModel(player, cache, auth, prefs)
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
}

private fun android.content.Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
