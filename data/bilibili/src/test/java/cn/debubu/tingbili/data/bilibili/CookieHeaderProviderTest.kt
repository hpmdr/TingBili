package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieHeaderProviderTest {

    private class FakeStore(var session: BiliSession? = null) : AuthStore {
        override fun load(): BiliSession? = session
        override fun save(session: BiliSession?) {
            this.session = session
        }
    }

    private class CountingBuvid(var cookie: String? = "buvid3=x") : BuvidProvider() {
        var calls = 0
        override fun cookie(): String? {
            calls++
            return cookie
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun session(uid: Long = 123L) = BiliSession(
        sessdata = "SESS",
        biliJct = "CSRF",
        uid = uid,
    )

    @Test
    fun `build joins session and buvid, drops blanks`() {
        val p = CookieHeaderProvider(AuthRepository(json, FakeStore()), CountingBuvid())
        assertNull(p.build(null, null))
        assertNull(p.build(null, "  ; "))
        val h = p.build(session(), "buvid3=x")
        assertTrue(h!!.contains("SESSDATA=SESS"))
        assertTrue(h.contains("buvid3=x"))
    }

    @Test
    fun `get caches within window, refetches after session change`() {
        val store = FakeStore(null)
        val auth = AuthRepository(json, store)
        val buvid = CountingBuvid("buvid3=x")
        val p = CookieHeaderProvider(auth, buvid)
        val first = p.get()
        val second = p.get()
        assertEquals(first, second)
        assertEquals(1, buvid.calls)
        // 登录换了 session 实例：缓存即时失效，不会沿用游客头
        auth.save(session(456L))
        val third = p.get()
        assertTrue(third!!.contains("DedeUserID=456"))
        assertEquals(2, buvid.calls)
    }

    @Test
    fun `logout drops session part`() {
        val store = FakeStore(session())
        val auth = AuthRepository(json, store)
        val buvid = CountingBuvid("buvid3=x")
        val p = CookieHeaderProvider(auth, buvid)
        assertTrue(p.get()!!.contains("SESSDATA"))
        auth.save(null)
        val after = p.get()
        assertTrue(after == "buvid3=x")
    }
}
