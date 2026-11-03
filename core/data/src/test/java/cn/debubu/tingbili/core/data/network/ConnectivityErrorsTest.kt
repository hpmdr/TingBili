package cn.debubu.tingbili.core.data.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityErrorsTest {

    @Test
    fun `unknown host is connectivity`() {
        assertTrue(isConnectivityError(UnknownHostException("x")))
    }

    @Test
    fun `connect refused is connectivity`() {
        assertTrue(isConnectivityError(ConnectException("Failed to connect")))
    }

    @Test
    fun `read timeout is connectivity`() {
        assertTrue(isConnectivityError(SocketTimeoutException("timeout")))
    }

    @Test
    fun `wrapped cause chain is connectivity`() {
        assertTrue(isConnectivityError(IOException("req failed", UnknownHostException("dns"))))
    }

    @Test
    fun `mark message is connectivity even if cause lost`() {
        assertTrue(isConnectivityError(IOException("$CONNECTIVITY_MARK，请检查网络后重试")))
    }

    @Test
    fun `rate limit is not connectivity`() {
        assertFalse(isConnectivityError(RuntimeException("请求太频繁（412），稍后再试")))
    }

    @Test
    fun `resolve failed without cause is not connectivity`() {
        assertFalse(isConnectivityError(IOException("音源解析失败（可能触发风控），请稍后再试")))
    }

    @Test
    fun `null is not connectivity`() {
        assertFalse(isConnectivityError(null))
    }

    @Test
    fun `error text with mark is connectivity`() {
        assertTrue(isConnectivityErrorText("${CONNECTIVITY_MARK}，请检查网络后重试"))
    }

    @Test
    fun `error text of unresolved host is connectivity`() {
        assertTrue(isConnectivityErrorText("Unable to resolve host \"api.bilibili.com\""))
    }

    @Test
    fun `plain failure text is not connectivity`() {
        assertFalse(isConnectivityErrorText("音源解析失败（可能触发风控），请稍后再试"))
        assertFalse(isConnectivityErrorText(null))
    }
}
