package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import cn.debubu.tingbili.data.bilibili.auth.QrStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {

    private val server = MockWebServer()
    private val saved = mutableListOf<BiliSession?>()
    private val store = object : AuthStore {
        override fun load(): BiliSession? = null
        override fun save(session: BiliSession?) {
            saved.add(session)
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val repo = AuthRepository(json, store).also {
        val base = server.url("/").toString().trimEnd('/')
        it.passportBase = base
        it.apiBase = base
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `generateQr parses key and url`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"code":0,"message":"ok","data":{"url":"https://gourl/qr","qrcode_key":"KEY123"}}"""
        ))
        val res = repo.generateQr()
        assertTrue(res.isSuccess)
        assertEquals("KEY123", res.getOrThrow().qrcodeKey)
    }

    @Test
    fun `pollQr maps status codes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":86101,"message":"未扫码"}"""))
        assertTrue(repo.pollQr("K") is QrStatus.Waiting)
        server.enqueue(MockResponse().setBody("""{"code":86090,"message":"已扫码"}"""))
        assertTrue(repo.pollQr("K") is QrStatus.Scanned)
        server.enqueue(MockResponse().setBody("""{"code":86038,"message":"过期"}"""))
        assertTrue(repo.pollQr("K") is QrStatus.Expired)
        server.enqueue(MockResponse().setBody(
            """{"code":0,"message":"ok","data":{"url":"${server.url("/done")}","refresh_token":"RT"}}"""
        ))
        val ok = repo.pollQr("K")
        assertTrue(ok is QrStatus.Success)
        assertEquals("RT", (ok as QrStatus.Success).refreshToken)
    }

    @Test
    fun `finishQrLogin walks redirects and saves session`() = runTest {
        // 跳转链：/go -> /landing，沿途各下一颗 Cookie
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/landing").toString())
                .addHeader("Set-Cookie", "SESSDATA=SESS123; Path=/")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "bili_jct=JCT1; Path=/")
                .addHeader("Set-Cookie", "DedeUserID=42; Path=/")
                .addHeader("Set-Cookie", "DedeUserID__ckMd5=CK; Path=/")
        )
        server.enqueue(MockResponse().setBody(
            """{"code":0,"message":"ok","data":{"isLogin":true,"mid":42,"uname":"测试用户","face":"https://f/x.jpg"}}"""
        ))
        val res = repo.finishQrLogin(server.url("/go").toString())
        assertTrue(res.isSuccess)
        val s = res.getOrThrow()
        assertEquals("SESS123", s.sessdata)
        assertEquals(42L, s.uid)
        assertEquals("测试用户", s.userName)
        assertEquals("CK", s.extras["DedeUserID__ckMd5"])
        assertEquals(1, saved.size)
        assertEquals(s, repo.sessionFlow.value)
    }

    @Test
    fun `loginWithCookies rejects garbage without network`() = runTest {
        val res = repo.loginWithCookies("just some text")
        assertTrue(res.isFailure)
        assertTrue(saved.isEmpty())
        assertNull(repo.sessionFlow.value)
    }

    @Test
    fun `parseCookieHeader requires sessdata and uid`() {
        assertNull(BiliSession.parseCookieHeader("bili_jct=J; DedeUserID=42"))
        assertNull(BiliSession.parseCookieHeader("SESSDATA=S"))
        val ok = BiliSession.parseCookieHeader(
            "buvid3=OLD; SESSDATA=S1; bili_jct=J1; DedeUserID=7; DedeUserID__ckMd5=C; fingerprint=F"
        )
        assertEquals("S1", ok?.sessdata)
        assertEquals(7L, ok?.uid)
        // 陈旧 buvid/fingerprint 必须丢弃，只白名单回传
        assertTrue(ok?.toCookieHeader()?.contains("buvid3") == false)
        assertTrue(ok?.toCookieHeader()?.contains("DedeUserID__ckMd5=C") == true)
    }

    @Test
    fun `maintainSession without session does nothing`() = runTest {
        assertTrue(!repo.maintainSession())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `maintainSession skips when refresh not needed`() = runTest {
        repo.save(BiliSession("S", "J", 9L, refreshToken = "RT0"))
        server.enqueue(MockResponse().setBody(
            """{"code":0,"data":{"refresh":false,"timestamp":123}}"""
        ))
        assertTrue(repo.maintainSession())
        assertEquals(1, server.requestCount)
        // 会话原样保留
        assertEquals("RT0", repo.sessionFlow.value?.refreshToken)
    }

    @Test
    fun `maintainSession refreshes full chain`() = runTest {
        repo.save(BiliSession("OLD_S", "OLD_J", 9L, "老名字", "", "RT0"))
        repo.correspondBase = server.url("/").toString().trimEnd('/')
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(req: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = req.path.orEmpty()
                return when {
                    path.startsWith("/x/passport-login/web/cookie/info") ->
                        MockResponse().setBody("""{"code":0,"data":{"refresh":true}}""")
                    path.startsWith("/correspond/1/") ->
                        MockResponse().setBody("""<html>"refresh_token":"CSRF123"</html>""")
                    path.startsWith("/x/passport-login/web/cookie/refresh") ->
                        MockResponse()
                            .addHeader("Set-Cookie", "SESSDATA=NEW_S; Path=/")
                            .addHeader("Set-Cookie", "bili_jct=NEW_J; Path=/")
                            .addHeader("Set-Cookie", "DedeUserID=9; Path=/")
                            .setBody("""{"code":0,"data":{"refresh_token":"RT1"}}""")
                    path.startsWith("/x/passport-login/web/confirm/refresh") ->
                        MockResponse().setBody("""{"code":0}""")
                    path.startsWith("/x/web-interface/nav") ->
                        MockResponse().setBody(
                            """{"code":0,"data":{"isLogin":true,"mid":9,"uname":"新名字","face":"https://f/n.jpg"}}"""
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        assertTrue(repo.maintainSession())
        val s = repo.sessionFlow.value
        assertEquals("NEW_S", s?.sessdata)
        assertEquals("NEW_J", s?.biliJct)
        assertEquals("RT1", s?.refreshToken)
        assertEquals("新名字", s?.userName)
        // info + correspond + refresh + confirm + nav = 5 次请求
        assertEquals(5, server.requestCount)
    }
}
