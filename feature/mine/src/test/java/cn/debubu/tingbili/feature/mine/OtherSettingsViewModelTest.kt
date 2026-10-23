package cn.debubu.tingbili.feature.mine

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.cache.CacheManager
import cn.debubu.tingbili.core.data.cache.CacheStats
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerHandle
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto
import cn.debubu.tingbili.data.bilibili.dto.DashAudio
import cn.debubu.tingbili.data.bilibili.dto.DashData
import cn.debubu.tingbili.data.bilibili.dto.FavDetailDto
import cn.debubu.tingbili.data.bilibili.dto.FavFolderDto
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlData
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ToviewDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OtherSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: PreferencesRepository
    private lateinit var audioCache: SimpleCache
    private lateinit var fakeManager: FakeCacheManager
    private lateinit var player: PlayerManager

    class FakeCacheManager : CacheManager {
        var stats = CacheStats()
        var lastExclude: String? = "UNSET"
        override suspend fun stats(): CacheStats = stats
        override suspend fun clearAudio(excludeKey: String?): Long {
            lastExclude = excludeKey
            return 11L
        }
        override suspend fun clearImages(): Long = 22L
        override suspend fun clearSubtitles(): Int = 3
        override suspend fun clearAll(excludeAudioKey: String?): CacheStats {
            lastExclude = excludeAudioKey
            return CacheStats(11, 22, 33, 3)
        }
    }

    class FakePlayerHandle : PlayerHandle {
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override val isPlaying: Boolean get() = false
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override val currentMediaItemIndex: Int get() = 0
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
        override fun setAudioOffloadEnabled(enabled: Boolean) {}
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

    class FakeBiliApi : BiliApi {
        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto(code = 0)
        override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto =
            PlayUrlDto(code = 0, data = PlayUrlData(dash = DashData(audio = listOf(DashAudio(baseUrl = "https://audio.example.com/a.mp3")))))
        override suspend fun related(bvid: String): RelatedDto = RelatedDto()
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
        override suspend fun favFolders(pn: Int, ps: Int, upMid: Long): FavFolderDto = FavFolderDto()
        override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): FavDetailDto = FavDetailDto()
        override suspend fun toview(pn: Int, ps: Int): ToviewDto = ToviewDto()
        override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): BiliHistoryDto = BiliHistoryDto()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_other_settings_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
        audioCache = SimpleCache(
            File(context.cacheDir, "other_cache_${System.nanoTime()}"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context)
        )
        fakeManager = FakeCacheManager()
        val resolvers = cn.debubu.tingbili.data.bilibili.BiliMediaResolvers(BiliRepository(FakeBiliApi()))
        player = PlayerManager(FakePlayerHandle(), FakeHistoryDao(), prefs, resolvers, resolvers, resolvers, FakePlayUrlCacheDao(), FakePlayQueueDao(), context)
    }

    @After
    fun tearDown() {
        audioCache.release()
        Dispatchers.resetMain()
    }

    @Test fun `set cache max persists`() = runTest(dispatcher) {
        val vm = OtherSettingsViewModel(prefs, fakeManager, player)
        vm.setCacheMaxMb(1024)
        advanceUntilIdle()
        assertEquals(1024, prefs.cacheMaxMb.first())
    }

    @Test fun `cache max coerced in range 100 to 2048`() = runTest(dispatcher) {
        val vm = OtherSettingsViewModel(prefs, fakeManager, player)
        vm.setCacheMaxMb(10000)
        advanceUntilIdle()
        assertEquals(2048, prefs.cacheMaxMb.first())
        vm.setCacheMaxMb(1)
        advanceUntilIdle()
        assertEquals(100, prefs.cacheMaxMb.first())
    }

    @Test fun `stats exposed from manager`() = runTest(dispatcher) {
        fakeManager.stats = CacheStats(audioBytes = 1000, imageBytes = 2000, subtitleBytes = 300, subtitleCount = 2)
        val vm = OtherSettingsViewModel(prefs, fakeManager, player)
        advanceUntilIdle()
        assertEquals(3300L, vm.cacheStats.first().totalBytes)
        assertEquals(2, vm.cacheStats.first().subtitleCount)
    }

    @Test fun `clear audio excludes nothing when idle`() = runTest(dispatcher) {
        val vm = OtherSettingsViewModel(prefs, fakeManager, player)
        vm.clearAudioCache()
        advanceUntilIdle()
        assertNull(fakeManager.lastExclude)
        assertTrue(vm.notice.first()?.contains("音频") == true)
    }

    @Test fun `clear audio excludes playing key`() = runTest(dispatcher) {
        val vm = OtherSettingsViewModel(prefs, fakeManager, player)
        try {
            player.play(listOf(Track("BV1xx", 1001, "T1", "a", "", 60000, null)), 0)
            runCurrent()
            vm.clearAudioCache()
            runCurrent()
        } finally {
            player.release()
        }
        advanceUntilIdle()
        assertEquals("BV1xx:1001", fakeManager.lastExclude)
        assertTrue(vm.notice.first()?.contains("跳过") == true)
    }

    @Test fun `clear images and subtitles report results`() = runTest(dispatcher) {
        val vm = OtherSettingsViewModel(prefs, fakeManager, player)
        vm.clearImageCache()
        advanceUntilIdle()
        assertTrue(vm.notice.first()?.contains("图片") == true)
        vm.clearSubtitleCache()
        advanceUntilIdle()
        assertTrue(vm.notice.first()?.contains("3") == true)
        vm.clearAllCaches()
        advanceUntilIdle()
        assertTrue(vm.notice.first()?.contains("全部") == true)
    }
}
