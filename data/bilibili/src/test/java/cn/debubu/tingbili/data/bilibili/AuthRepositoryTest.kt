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
    fun `finishWebLogin walks redirects and saves session`() = runTest {
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
        val res = repo.finishWebLogin(server.url("/go").toString())
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

    @Test
    fun `sendSms returns captcha key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"captcha_key":"CK1"}}"""))
        val ticket = cn.debubu.tingbili.data.bilibili.auth.AuthRepository.GeeTicket("T", "G", "C")
        val result = AuthRepository.GeeResult("V", "S", "C")
        val res = repo.sendSms(86, "13800000000", ticket, result)
        assertTrue(res.isSuccess)
        assertEquals("CK1", res.getOrThrow())
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("captcha_key") || body.contains("tel=13800000000"))
    }

    @Test
    fun `smsLogin completes cookie exchange and saves`() = runTest {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(req: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = req.path.orEmpty()
                return when {
                    path.startsWith("/x/passport-login/web/login/sms") ->
                        MockResponse().setBody(
                            """{"code":0,"data":{"url":"${server.url("/go")}","refresh_token":"RTS","timestamp":1}}"""
                        )
                    path.startsWith("/go") ->
                        MockResponse().setResponseCode(302)
                            .addHeader("Location", server.url("/land").toString())
                            .addHeader("Set-Cookie", "SESSDATA=SS; Path=/")
                    path.startsWith("/land") ->
                        MockResponse().setResponseCode(200)
                            .addHeader("Set-Cookie", "DedeUserID=11; Path=/")
                            .addHeader("Set-Cookie", "bili_jct=JJ; Path=/")
                    path.startsWith("/x/web-interface/nav") ->
                        MockResponse().setBody(
                            """{"code":0,"data":{"isLogin":true,"mid":11,"uname":"短信用户","face":""}}"""
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val res = repo.smsLogin(86, "13800000000", "123456", "CK1")
        assertTrue(res.isSuccess)
        val s = res.getOrThrow()
        assertEquals("SS", s.sessdata)
        assertEquals(11L, s.uid)
        assertEquals("短信用户", s.userName)
        assertEquals("RTS", s.refreshToken)
    }

    @Test
    fun `smsLogin risk status fails with guidance`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"code":0,"data":{"status":2,"message":"环境存在风险","url":"https://risk/x","refresh_token":""}}"""
        ))
        val res = repo.smsLogin(86, "13800000000", "123456", "CK1")
        assertTrue(res.isFailure)
        assertTrue((res.exceptionOrNull()?.message ?: "").contains("扫码"))
    }

    @Test
    fun `rsaEncrypt round trips with generated keypair`() {
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(1024)
        val pair = kpg.generateKeyPair()
        val der64 = java.util.Base64.getEncoder().encodeToString(pair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n$der64\n-----END PUBLIC KEY-----"
        val cipher = repo.rsaEncrypt(pem, "HASH123secret")
        val dec = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        dec.init(javax.crypto.Cipher.DECRYPT_MODE, pair.private)
        val plain = String(dec.doFinal(java.util.Base64.getDecoder().decode(cipher)), Charsets.UTF_8)
        assertEquals("HASH123secret", plain)
    }
}
