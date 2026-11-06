package cn.debubu.tingbili.data.bilibili

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 主链挂掉后的备链兜底：首次解析暂存备链，后续同 key 直接给下一条，不再打 playurl；
 * 备链取完回落网络；换音质偏好是不同 key，不吃旧档备链。
 */
class BiliMediaResolversTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: PreferencesRepository
    private lateinit var resolvers: BiliMediaResolvers

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val repo = BiliRepository(retrofit.create(BiliApi::class.java), OkHttpClient(), json)
        val dir = Files.createTempDirectory("bili-resolvers-test").toFile()
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { File(dir, "test.preferences_pb") },
        )
        prefs = PreferencesRepository(ds)
        resolvers = BiliMediaResolvers(repo, prefs)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun playUrl(primary: String, vararg backups: String): String {
        val backupJson = backups.joinToString(",") { "\"$it\"" }
        return """{"code":0,"data":{"dash":{"audio":[{"baseUrl":"$primary","id":30232,""" +
            """"bandwidth":132000,"backup_url":[$backupJson]}],"video":[]},"durl":[]}}"""
    }

    private fun enqueuePlayUrl(body: String) {
        server.enqueue(
            MockResponse().setBody(body).setResponseCode(200)
                .addHeader("Content-Type", "application/json")
        )
    }

    @Test
    fun `resolve serves primary then staged backups without extra requests`() = runTest {
        enqueuePlayUrl(playUrl("https://p.example.com/a.m4s", "https://b1.example.com/a.m4s", "https://b2.example.com/a.m4s"))
        enqueuePlayUrl(playUrl("https://p2.example.com/a.m4s"))

        val first = resolvers.resolve("BV1", 7L)
        assertEquals("https://p.example.com/a.m4s", first?.url)
        assertEquals(
            listOf("https://b1.example.com/a.m4s", "https://b2.example.com/a.m4s"),
            first?.backupUrls,
        )
        assertEquals(132000, first?.bandwidthBps)
        assertEquals(30232, first?.audioCode)
        assertEquals(1, server.requestCount)

        // 模拟播放失败重试（PlayerManager 先删磁盘缓存再重解析）：直接给备链，不打网
        assertEquals("https://b1.example.com/a.m4s", resolvers.resolve("BV1", 7L)?.url)
        assertEquals(1, server.requestCount)
        assertEquals("https://b2.example.com/a.m4s", resolvers.resolve("BV1", 7L)?.url)
        assertEquals(1, server.requestCount)

        // 备链取完：回落网络重解析
        assertEquals("https://p2.example.com/a.m4s", resolvers.resolve("BV1", 7L)?.url)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `quality switch does not consume staged backups of another quality`() = runTest {
        enqueuePlayUrl(playUrl("https://p.example.com/a.m4s", "https://b1.example.com/a.m4s"))
        enqueuePlayUrl(playUrl("https://q64.example.com/a.m4s"))

        assertEquals("https://p.example.com/a.m4s", resolvers.resolve("BV1", 7L)?.url)
        assertEquals(1, server.requestCount)

        // 换档（refreshCurrentUrl 同样删缓存重解析）：新偏好必须走网络，不能吃 132K 的备链
        prefs.setAudioQuality(30216)
        assertEquals("https://q64.example.com/a.m4s", resolvers.resolve("BV1", 7L)?.url)
        assertEquals(2, server.requestCount)
    }
}
