package cn.debubu.tingbili.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.SubtitleDiskDao
import cn.debubu.tingbili.core.data.db.SubtitleDiskEntity
import cn.debubu.tingbili.core.data.db.SubtitleRowSize
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.SubtitleCache
import cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto
import cn.debubu.tingbili.data.bilibili.dto.FavDetailDto
import cn.debubu.tingbili.data.bilibili.dto.FavFolderDto
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ToviewDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppCacheManagerTest {

    private val dispatcher = StandardTestDispatcher()

    class FakeSubtitleDiskDao : SubtitleDiskDao {
        val rows = mutableMapOf<String, SubtitleDiskEntity>()
        override suspend fun get(key: String): SubtitleDiskEntity? = rows[key]
        override suspend fun save(e: SubtitleDiskEntity) { rows[e.cacheKey] = e }
        override suspend fun prune(keep: Int) {
            rows.keys.sortedBy { rows[it]?.fetchedAt }.dropLast(keep).forEach { rows.remove(it) }
        }
        override suspend fun deleteByPrefix(prefix: String) {
            rows.keys.filter { it.startsWith(prefix) }.forEach { rows.remove(it) }
        }
        override suspend fun clear() { rows.clear() }
        override suspend fun rowSizes(): List<SubtitleRowSize> =
            rows.values.map { SubtitleRowSize(it.tracksJson, it.linesJson) }
    }

    class FakeBiliApi : BiliApi {
        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto(code = 0)
        override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
        override suspend fun related(bvid: String): RelatedDto = RelatedDto()
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
        override suspend fun favFolders(pn: Int, ps: Int, upMid: Long): FavFolderDto = FavFolderDto()
        override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): FavDetailDto = FavDetailDto()
        override suspend fun toview(pn: Int, ps: Int): ToviewDto = ToviewDto()
        override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): BiliHistoryDto = BiliHistoryDto()
    }

    private lateinit var context: Context
    private lateinit var audioCache: SimpleCache
    private lateinit var dao: FakeSubtitleDiskDao
    private lateinit var manager: AppCacheManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioCache = SimpleCache(
            File(context.cacheDir, "mgr_audio_${System.nanoTime()}"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context)
        )
        dao = FakeSubtitleDiskDao()
        val subtitleCache = SubtitleCache(BiliRepository(FakeBiliApi()), dao)
        manager = AppCacheManager(audioCache, dao, subtitleCache, context)
    }

    @After
    fun tearDown() {
        audioCache.release()
    }

    @Test fun `empty caches report zeros`() = runTest(dispatcher) {
        val stats = manager.stats()
        assertEquals(0L, stats.audioBytes)
        assertEquals(0, stats.subtitleCount)
        assertTrue(stats.imageBytes >= 0L)
        assertEquals(stats.audioBytes + stats.imageBytes + stats.subtitleBytes, stats.totalBytes)
    }

    @Test fun `subtitle stats count exact bytes`() = runTest(dispatcher) {
        dao.save(SubtitleDiskEntity("BV1:1", tracksJson = "[1]", linesJson = "[22]"))
        dao.save(SubtitleDiskEntity("BV2:2", tracksJson = "[333]", linesJson = "[]"))
        val stats = manager.stats()
        assertEquals(2, stats.subtitleCount)
        val expected = "[1]".toByteArray().size + "[22]".toByteArray().size +
            "[333]".toByteArray().size + "[]".toByteArray().size
        assertEquals(expected.toLong(), stats.subtitleBytes)
    }

    @Test fun `clear subtitles removes rows and reports count`() = runTest(dispatcher) {
        dao.save(SubtitleDiskEntity("BV1:1", tracksJson = "[1]", linesJson = "[2]"))
        advanceUntilIdle()
        assertEquals(1, manager.clearSubtitles())
        assertEquals(0, manager.stats().subtitleCount)
    }

    @Test fun `clear audio and images on empty caches are safe`() = runTest(dispatcher) {
        assertEquals(0L, manager.clearAudio())
        assertEquals(0L, manager.clearAudio("BV1:1"))
        assertTrue(manager.clearImages() >= 0L)
    }

    @Test fun `clear all aggregates freed amounts`() = runTest(dispatcher) {
        dao.save(SubtitleDiskEntity("BV1:1", tracksJson = "[1]", linesJson = "[2]"))
        val freed = manager.clearAll()
        assertEquals(1, freed.subtitleCount)
        assertTrue(freed.audioBytes >= 0L)
        assertTrue(freed.imageBytes >= 0L)
    }
}
