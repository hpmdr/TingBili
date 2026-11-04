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
    private lateinit var store: BiliCookieStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = BiliCookieStore()
        client = OkHttpClient.Builder()
            .addInterceptor(
                WbiInterceptor(
                    WbiSigner(Json { ignoreUnknownKeys = true }),
                    cookieProvider = { "SESSDATA=abc" },
                    cookieStore = store,
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

    /**
     * playurl 不能混入设备 buvid：账号绑的是浏览器那份，混入会让 dash.audio 静默降级
     * （Hi-Res/杜比消失，2026-11 大会员账号实测）。
     */
    @Test
    fun `playurl without session buvid skips stored device identity`() {
        store.onSetCookies(listOf("buvid3=NEW; Path=/", "b_nut=123; Path=/"))
        execute(Request.Builder().url(server.url("/x/player/playurl")).build())
        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(cookie.contains("SESSDATA=abc"))
        assertTrue(!cookie.contains("buvid3"))
        assertTrue(!cookie.contains("b_nut"))
    }

    /** 搜索相反：必须带 buvid，不带时服务端 code=0 但返回 v_voucher + 空结果 */
    @Test
    fun `search with session but no buvid fills stored device identity`() {
        store.onSetCookies(listOf("buvid3=NEW; Path=/", "b_nut=123; Path=/"))
        execute(Request.Builder().url(server.url("/x/web-interface/wbi/search/type")).build())
        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(cookie.contains("SESSDATA=abc"))
        assertTrue(cookie.contains("buvid3=NEW"))
        assertTrue(cookie.contains("b_nut=123"))
    }

    /** 会话自带 buvid（Cookie 导入）时，设备值只补缺、绝不覆盖 */
    @Test
    fun `session buvid wins over stored device identity`() {
        store.onSetCookies(listOf("buvid3=DEVICE; Path=/"))
        val ownClient = OkHttpClient.Builder()
            .addInterceptor(
                WbiInterceptor(
                    WbiSigner(Json { ignoreUnknownKeys = true }),
                    cookieProvider = { "SESSDATA=abc; buvid3=SESSION" },
                    cookieStore = store,
                )
            )
            .build()
        server.enqueue(MockResponse().setBody("{}"))
        ownClient.newCall(Request.Builder().url(server.url("/x/player/playurl")).build())
            .execute().close()
        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(cookie.contains("buvid3=SESSION"))
        assertTrue(!cookie.contains("buvid3=DEVICE"))
    }

    /** 搜索必须带 buvid：会话没有自带时，用 BuvidProvider 持久化的本机 buvid 兜底 */
    @Test
    fun `search fills buvid from provider when session has none`() {
        val c = OkHttpClient.Builder()
            .addInterceptor(
                WbiInterceptor(
                    WbiSigner(Json { ignoreUnknownKeys = true }),
                    cookieProvider = { "SESSDATA=abc" },
                    deviceCookieProvider = { "buvid3=GEN; buvid4=G4; b_nut=99" },
                )
            )
            .build()
        server.enqueue(MockResponse().setBody("{}"))
        c.newCall(Request.Builder().url(server.url("/x/web-interface/wbi/search/type")).build())
            .execute().close()
        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(cookie.contains("buvid3=GEN"))
        assertTrue(cookie.contains("b_nut=99"))
    }

    /** playurl 相反：会话无 buvid 时不发设备 buvid，否则音质静默降级 */
    @Test
    fun `playurl does not use provider buvid when session has none`() {
        val c = OkHttpClient.Builder()
            .addInterceptor(
                WbiInterceptor(
                    WbiSigner(Json { ignoreUnknownKeys = true }),
                    cookieProvider = { "SESSDATA=abc" },
                    deviceCookieProvider = { "buvid3=GEN; buvid4=G4; b_nut=99" },
                )
            )
            .build()
        server.enqueue(MockResponse().setBody("{}"))
        c.newCall(Request.Builder().url(server.url("/x/player/playurl")).build())
            .execute().close()
        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(!cookie.contains("buvid3"))
    }

    /** 游客请求必须带设备 Cookie：没有 buvid 的游客搜索必被软拦 */
    @Test
    fun `guest request carries stored device cookies`() {
        store.onSetCookies(listOf("buvid3=NEW; Path=/", "b_nut=123; Path=/"))
        execute(
            Request.Builder()
                .url(server.url("/x/player/playurl"))
                .header(WbiInterceptor.GUEST_HEADER, "true")
                .build()
        )
        val cookie = server.takeRequest().getHeader("Cookie")!!
        assertTrue(cookie.contains("buvid3=NEW"))
        assertTrue(cookie.contains("b_nut=123"))
    }
}
