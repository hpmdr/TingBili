package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class BiliRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: BiliRepository

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(BiliApi::class.java)
        repo = BiliRepository(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search parses tracks`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"result":[{"bvid":"BV1xx","title":"Test","author":"up","pic":"https://","duration":"5:00"}]}}"""
            ).setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.search("小说")
        assertTrue(r is Result.Success)
        val tracks = (r as Result.Success).data
        assertEquals(1, tracks.size)
        assertEquals("BV1xx", tracks[0].bvid)
    }

    @Test
    fun `search returns Error on non-zero code`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"code":-404,"message":"not found","data":null}""")
                .setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.search("不存在")
        assertTrue(r is Result.Error)
    }

    @Test
    fun `getView parses pages into tracks`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"bvid":"BV1xx","title":"My Video","pic":"https://cover","owner":{"name":"author1"},"pages":[{"cid":1001,"page":1,"part":"P1","duration":60},{"cid":1002,"page":2,"part":"P2","duration":120}],"subtitle":{"list":[]}}}"""
            ).setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.getView("BV1xx")
        assertTrue(r is Result.Success)
        val tracks = (r as Result.Success).data
        assertEquals(2, tracks.size)
        assertEquals(1001L, tracks[0].cid)
        assertEquals(1002L, tracks[1].cid)
        assertEquals("author1", tracks[0].author)
    }

    @Test
    fun `getPlayUrl parses dash audio url`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"dash":{"audio":[{"baseUrl":"https://audio.example.com/a.mp3","id":30280}],"video":[]},"durl":[]}}"""
            ).setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.getPlayUrl("BV1xx", 1001)
        assertTrue(r is Result.Success)
        assertEquals("https://audio.example.com/a.mp3", (r as Result.Success).data)
    }

    @Test
    fun `getPlayUrl fallback to durl when dash empty`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"durl":[{"url":"https://durl.example.com/v.mp4"}],"dash":{"audio":[]}}}"""
            ).setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.getPlayUrl("BV1xx", 1001)
        assertTrue(r is Result.Success)
        assertEquals("https://durl.example.com/v.mp4", (r as Result.Success).data)
    }

    @Test
    fun `getSubtitle parses lyric lines`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"data":{"body":[{"from":0.0,"to":1.5,"content":"hello"},{"from":1.5,"to":3.0,"content":"world"}]}}"""
            ).setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.getSubtitle("BV1xx", 1001)
        assertTrue(r is Result.Success)
        val lines = (r as Result.Success).data
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals("hello", lines[0].text)
        assertEquals(1500L, lines[1].timeMs)
    }

    @Test
    fun `getSubtitle empty body returns empty list`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"code":0,"data":{"body":[]}}""")
                .setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.getSubtitle("BV1xx", 1001)
        assertTrue(r is Result.Success)
        assertEquals(0, (r as Result.Success).data.size)
    }

    @Test
    fun `playUrl returns Error on failure code`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"code":-404,"message":"not found","data":null}""")
                .setResponseCode(200).addHeader("Content-Type", "application/json")
        )
        val r = repo.getPlayUrl("BV1xx", 1001)
        assertTrue(r is Result.Error)
    }
}
