package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
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
        // 字幕 URL 归一化后是 https，所以 mock 端点也得走 TLS，
        // 否则第二次请求会打到一个不存在的明文端口，被 fetchLyricLines 吞成空列表。
        val heldCert = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(heldCert).build()
        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(heldCert.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()

        val trustCerts = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(trustCerts)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(BiliApi::class.java)
        repo = BiliRepository(api, trustCerts, json)
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

    /** 第一跳返回字幕轨列表，第二跳返回正文——这才是 /x/player/wbi/v2 的真实两步流程 */
    @Test
    fun `getSubtitle fetches subtitle_url then parses body`() = runTest {
        val fileUrl = server.url("/subtitle.json").toString()
        server.enqueue(
            jsonResponse(
                """{"code":0,"data":{"subtitle":{"lan":"","lan_doc":"","subtitles":[
                   {"id":1,"lan":"ai-zh","lan_doc":"中文（自动生成）","type":1,"subtitle_url":"$fileUrl"}
                ]}}}"""
            )
        )
        server.enqueue(
            jsonResponse(
                """{"body":[{"from":0.0,"to":1.5,"content":"你好"},{"from":1.5,"to":3.0,"content":"世界"}]}"""
            )
        )
        val r = repo.getSubtitle("BV1xx", 1001)
        assertTrue(r is Result.Success)
        val lines = (r as Result.Success).data
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals("你好", lines[0].text)
        assertEquals(1500L, lines[1].timeMs)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `getSubtitle prefers manual track over ai`() = runTest {
        val aiUrl = server.url("/ai.json").toString()
        val manualUrl = server.url("/manual.json").toString()
        server.enqueue(
            jsonResponse(
                """{"code":0,"data":{"subtitle":{"subtitles":[
                   {"lan":"ai-zh","lan_doc":"中文（自动生成）","type":1,"subtitle_url":"$aiUrl"},
                   {"lan":"zh-CN","lan_doc":"中文","type":0,"subtitle_url":"$manualUrl"}
                ]}}}"""
            )
        )
        server.enqueue(jsonResponse("""{"body":[{"from":0.0,"to":1.0,"content":"人工字幕"}]}"""))
        val r = repo.getSubtitle("BV1xx", 1001) as Result.Success
        assertEquals("人工字幕", r.data[0].text)
        // 第一跳取列表，第二跳只下载人工那条，AI 那条不该被请求
        assertEquals(2, server.requestCount)
        assertEquals("/x/player/wbi/v2", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/manual.json", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun `getSubtitle returns empty when video has no subtitle`() = runTest {
        server.enqueue(jsonResponse("""{"code":0,"data":{"subtitle":{"subtitles":[]}}}"""))
        val r = repo.getSubtitle("BV1xx", 1001)
        assertTrue(r is Result.Success)
        assertEquals(0, (r as Result.Success).data.size)
        assertEquals(1, server.requestCount)
    }

    /** CDN 上该文件可能已被清理，按「没字幕」处理而不是抛错 */
    @Test
    fun `getSubtitle tolerates 404 on subtitle file`() = runTest {
        val fileUrl = server.url("/gone.json").toString()
        server.enqueue(
            jsonResponse("""{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"ai-zh","type":1,"subtitle_url":"$fileUrl"}]}}}""")
        )
        server.enqueue(MockResponse().setResponseCode(404))
        val r = repo.getSubtitle("BV1xx", 1001)
        assertTrue(r is Result.Success)
        assertEquals(0, (r as Result.Success).data.size)
    }

    @Test
    fun `getSubtitle returns Error on failure code`() = runTest {
        server.enqueue(jsonResponse("""{"code":-404,"message":"not found","data":null}"""))
        val r = repo.getSubtitle("BV1xx", 1001)
        assertTrue(r is Result.Error)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setBody(body)
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")

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
