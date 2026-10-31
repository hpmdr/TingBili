package cn.debubu.tingbili.feature.favorites

import cn.debubu.tingbili.core.data.Result
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BvRefreshFailureTest {

    @Test
    fun `invalid bv error is marked invalid`() {
        val state = Result.Error("稿件不可见").toBvRefreshFailure()

        assertTrue(state.invalid)
        assertEquals("稿件不可见", state.reason)
    }

    @Test
    fun `deleted bv error is marked invalid`() {
        val state = Result.Error("啥都木有").toBvRefreshFailure()

        assertTrue(state.invalid)
        assertEquals("啥都木有", state.reason)
    }

    @Test
    fun `io error maps to network message`() {
        val state = Result.Error("timeout", IOException("timeout")).toBvRefreshFailure()

        assertFalse(state.invalid)
        assertEquals("网络异常，请检查网络连接", state.reason)
    }

    @Test
    fun `blank error falls back to generic reason`() {
        val state = Result.Error("").toBvRefreshFailure()

        assertFalse(state.invalid)
        assertEquals("刷新失败", state.reason)
    }

    @Test
    fun `server error text is kept`() {
        val state = Result.Error("请求过于频繁").toBvRefreshFailure()

        assertFalse(state.invalid)
        assertEquals("请求过于频繁", state.reason)
    }
}
