package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleData
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleInfo
import cn.debubu.tingbili.data.bilibili.dto.SubtitleItem
import cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto
import cn.debubu.tingbili.data.bilibili.dto.FavDetailDto
import cn.debubu.tingbili.data.bilibili.dto.FavFolderDto
import cn.debubu.tingbili.data.bilibili.dto.ToviewDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import cn.debubu.tingbili.core.data.db.SubtitleDiskDao
import cn.debubu.tingbili.core.data.db.SubtitleDiskEntity
import cn.debubu.tingbili.core.data.db.SubtitleRowSize
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SubtitleCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: SubtitleCache
    private lateinit var fakeDisk: FakeSubtitleDiskDao
    private var subtitleCalls = 0
    private lateinit var subUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // loadSubtitleLines 不改写传入 url，明文 localhost 可直测（toTracks 那侧才强制 https）
        subUrl = server.url("/s.json").toString()
        val api = object : BiliApi {
            override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto()
            override suspend fun view(bvid: String): ViewDto = ViewDto()
            override suspend fun related(bvid: String): RelatedDto = RelatedDto()
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
            override suspend fun favFolders(pn: Int, ps: Int, upMid: Long): FavFolderDto = FavFolderDto()
            override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): FavDetailDto = FavDetailDto()
            override suspend fun toview(pn: Int, ps: Int): ToviewDto = ToviewDto()
            override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): BiliHistoryDto = BiliHistoryDto()
            override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto {
                subtitleCalls++
                return SubtitleDto(
                    code = 0,
                    data = SubtitleData(
                        subtitle = SubtitleInfo(
                            lan = "zh-CN", lanDoc = "中文",
                            subtitles = listOf(SubtitleItem(id = 1, lan = "zh-CN", lanDoc = "中文", subtitleUrl = subUrl))
                        )
                    )
                )
            }
        }
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        fakeDisk = FakeSubtitleDiskDao()
        cache = SubtitleCache(BiliRepository(api, OkHttpClient(), json), fakeDisk)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `tracks cached per bvid+cid`() = runTest {
        val first = cache.tracks("BV1xx", 1001L)
        val second = cache.tracks("BV1xx", 1001L)
        assertEquals(1, first.size)
        assertEquals(first, second)
        assertEquals(1, subtitleCalls)
        // 不同 cid 重新拉
        cache.tracks("BV1xx", 1002L)
        assertEquals(2, subtitleCalls)
    }

    @Test
    fun `lines downloaded once per url`() = runTest {
        server.enqueue(MockResponse().setBody("""{"body":[{"from":1.0,"to":2.0,"content":"你好"}]}""").setResponseCode(200))
        val first = cache.lines(subUrl)
        val second = cache.lines(subUrl)
        assertEquals(1, first.size)
        assertEquals("你好", first[0].text)
        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `linesResult propagates http failure and retries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val first = cache.linesResult(subUrl)
        assertEquals(true, first is cn.debubu.tingbili.core.data.Result.Error)
        // 失败不缓存：再调重新下载
        server.enqueue(MockResponse().setBody("""{"body":[{"from":1.0,"to":2.0,"content":"好了"}]}""").setResponseCode(200))
        val second = cache.linesResult(subUrl)
        assertEquals(true, second is cn.debubu.tingbili.core.data.Result.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `lines cache evicts oldest beyond cap`() = runTest {
        repeat(7) {
            server.enqueue(MockResponse().setBody("""{"body":[{"from":1.0,"to":2.0,"content":"x"}]}""").setResponseCode(200))
        }
        val urls = (1..6).map { server.url("/s$it.json").toString() }
        urls.forEach { cache.lines(it) }
        assertEquals(6, server.requestCount)
        // 正文上限 5 条：最早的 u1 被挤掉，重下一遍
        cache.lines(urls[0])
        assertEquals(7, server.requestCount)
        // 没被挤的命中缓存，不再请求
        cache.lines(urls[5])
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `empty lines are not cached`() = runTest {
        server.enqueue(MockResponse().setBody("""{"body":[]}""").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"body":[{"from":1.0,"to":2.0,"content":"后补"}]}""").setResponseCode(200))
        assertEquals(0, cache.lines(subUrl).size)
        assertEquals(1, cache.lines(subUrl).size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `disk snapshot roundtrips and reports freshness`() = runTest {
        assertEquals(null, cache.cached("BV1xx", 1001L))
        val tracks = listOf(cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack("zh-CN", "中文", subUrl, false))
        val lines = listOf(cn.debubu.tingbili.data.bilibili.model.LyricLine(1000L, "你好"))
        cache.store("BV1xx", 1001L, tracks, 0, lines, "中文", false)
        val got = cache.cached("BV1xx", 1001L)!!
        assertEquals(tracks, got.tracks)
        assertEquals(lines, got.lines)
        assertEquals(true, got.isFresh())
        assertEquals(false, got.isFresh(got.fetchedAt + SubtitleCache.SUBTITLE_DISK_TTL_MS + 1))
        // 空正文不落盘：读回仍是旧快照
        cache.store("BV1xx", 1001L, tracks, 0, emptyList(), "中文", false)
        assertEquals(lines, cache.cached("BV1xx", 1001L)!!.lines)
    }

    @Test
    fun `invalidateBv clears memory and disk`() = runTest {
        cache.tracks("BV1xx", 1001L)
        assertEquals(1, subtitleCalls)
        val tracks = listOf(cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack("zh-CN", "中文", subUrl, false))
        cache.store("BV1xx", 1001L, tracks, 0, listOf(cn.debubu.tingbili.data.bilibili.model.LyricLine(1L, "x")), "中文", false)
        assertNotNull(cache.cached("BV1xx", 1001L))
        cache.invalidateBv("BV1xx")
        assertNull(cache.cached("BV1xx", 1001L))
        cache.tracks("BV1xx", 1001L)
        assertEquals(2, subtitleCalls)
    }

    @Test
    fun `disk prune keeps newest 50`() = runTest {
        val tracks = listOf(cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack("zh-CN", "中文", subUrl, false))
        val lines = listOf(cn.debubu.tingbili.data.bilibili.model.LyricLine(1000L, "x"))
        repeat(55) { i -> cache.store("BV$i", 1L, tracks, 0, lines, "中文", false) }
        assertEquals(SubtitleCache.MAX_DISK_ENTRIES, fakeDisk.size())
    }
}

/** DAO 内存替身：语义对齐 Room（REPLACE + 按新旧裁剪） */
private class FakeSubtitleDiskDao : SubtitleDiskDao {
    private val map = LinkedHashMap<String, SubtitleDiskEntity>()
    override suspend fun get(key: String): SubtitleDiskEntity? = map[key]
    override suspend fun save(e: SubtitleDiskEntity) { map[e.cacheKey] = e }
    override suspend fun prune(keep: Int) {
        val drop = map.values.sortedByDescending { it.fetchedAt }.drop(keep).map { it.cacheKey }
        drop.forEach { map.remove(it) }
    }
    override suspend fun clear() = map.clear()
    override suspend fun deleteByPrefix(prefix: String) { map.keys.filter { it.startsWith(prefix) }.forEach { map.remove(it) } }
    override suspend fun rowSizes(): List<SubtitleRowSize> = map.values.map { SubtitleRowSize(it.tracksJson, it.linesJson) }
    fun size(): Int = map.size
}
