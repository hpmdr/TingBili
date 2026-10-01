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
        override fun prepare() {}
        override fun play() { playCalled = true }
        override fun pause() {}
        override fun seekTo(positionMs: Long) { seekToPos = positionMs; _pos = positionMs }
        override fun setPlaybackSpeed(speed: Float) {}
        override fun addListener(listener: Player.Listener) {}
        override fun release() {}
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
        override suspend fun view(bvid: String): ViewDto = viewResult
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto {
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
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
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
        playerManager = PlayerManager(fakePlayer, fakeDao, prefs, repo, context)
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
