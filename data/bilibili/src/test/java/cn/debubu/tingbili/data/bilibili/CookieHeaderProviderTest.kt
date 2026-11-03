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
    fun `build joins session and drops blanks`() {
        val p = CookieHeaderProvider(AuthRepository(json, FakeStore()), CountingBuvid())
        assertNull(p.build(null, null))
        assertNull(p.build(null, "  ; "))
        val h = p.build(session(), "buvid3=x")
        assertTrue(h!!.contains("SESSDATA=SESS"))
        // 登录态没有自带 buvid 时不发本机生成的那份：账号绑定的是别的 buvid，混用会 -351
        assertTrue(!h.contains("buvid3"))
        // 游客才带本机 buvid
        assertEquals("buvid3=x", p.build(null, "buvid3=x"))
    }

    /** Cookie 导入的账号已经和浏览器那份 buvid 绑定，换成本机新生成的会被风控 -351 */
    @Test
    fun `imported buvid wins over generated one`() {
        val imported = session().copy(extras = mapOf("buvid3" to "B3", "buvid4" to "B4"))
        val p = CookieHeaderProvider(AuthRepository(json, FakeStore(imported)), CountingBuvid("buvid3=NEW"))
        val h = p.build(imported, "buvid3=NEW")!!
        assertTrue(h.contains("buvid3=B3"))
        assertTrue(h.contains("buvid4=B4"))
        assertTrue(!h.contains("NEW"))
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
