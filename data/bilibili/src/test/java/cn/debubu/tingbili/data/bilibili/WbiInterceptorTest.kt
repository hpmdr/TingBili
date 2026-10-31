package cn.debubu.tingbili.data.bilibili

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 拦截器：UA/Referer、登录 Cookie 注入、游客标记跳 Cookie、WBI 只在指定路径签名。 */
class WbiInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(
                WbiInterceptor(
                    WbiSigner(Json { ignoreUnknownKeys = true }),
                    cookieProvider = { "SESSDATA=abc" },
                )
            )
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun execute(request: Request) {
        server.enqueue(MockResponse().setBody("{}"))
        client.newCall(request).execute().close()
    }

    @Test
    fun `logged in request carries cookie and headers`() {
        execute(Request.Builder().url(server.url("/x/player/playurl")).build())
        val recorded = server.takeRequest()
        assertEquals("SESSDATA=abc", recorded.getHeader("Cookie"))
        assertTrue(recorded.getHeader("User-Agent")!!.contains("Mozilla"))
        assertTrue(recorded.getHeader("Referer")!!.startsWith("https://"))
    }

    @Test
    fun `guest marker drops cookie and removes itself`() {
        execute(
            Request.Builder()
                .url(server.url("/x/player/playurl"))
                .header(WbiInterceptor.GUEST_HEADER, "true")
                .build()
        )
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Cookie"))
        assertNull(recorded.getHeader(WbiInterceptor.GUEST_HEADER))
    }

    @Test
    fun `guest marker false keeps cookie`() {
        execute(
            Request.Builder()
                .url(server.url("/x/player/playurl"))
                .header(WbiInterceptor.GUEST_HEADER, "false")
                .build()
        )
        assertEquals("SESSDATA=abc", server.takeRequest().getHeader("Cookie"))
    }
}
