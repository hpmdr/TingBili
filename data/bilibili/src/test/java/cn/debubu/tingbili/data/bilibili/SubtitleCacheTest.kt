package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleData
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleInfo
import cn.debubu.tingbili.data.bilibili.dto.SubtitleItem
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SubtitleCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: SubtitleCache
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
            override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto = PlayUrlDto(code = 0)
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
        cache = SubtitleCache(BiliRepository(api, OkHttpClient(), json))
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
}
