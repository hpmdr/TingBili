package cn.debubu.tingbili.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放失败弹窗门控（74ad19b 之后断网静默的回归点）：
 * 离线 + 断网类错误必须弹，否则横幅只说明"网络断开"，用户不知道这一集起播失败。
 */
class PlaybackErrorDialogGateTest {

    private fun gate(
        errorMessage: String? = "网络连接失败，请检查网络后重试",
        isLoading: Boolean = false,
        online: Boolean = false,
        autoRetryExhausted: Boolean = false,
    ) = shouldShowPlaybackErrorDialog(errorMessage, isLoading, online, autoRetryExhausted)

    @Test
    fun `offline connectivity error shows dialog`() {
        assertTrue(gate(online = false))
    }

    /** 直链缓存命中时走 HttpDataSource：断网文案是 OkHttp 的 Unable to resolve host */
    @Test
    fun `offline unresolved host text shows dialog`() {
        assertTrue(
            gate(
                errorMessage = "Unable to resolve host \"api.bilibili.com\": No address associated with hostname",
                online = false,
            ),
        )
    }

    @Test
    fun `offline non connectivity error stays silent`() {
        assertFalse(
            gate(
                errorMessage = "音源解析失败（可能触发风控），请稍后再试",
                online = false,
            ),
        )
    }

    @Test
    fun `loading suppresses dialog`() {
        assertFalse(gate(isLoading = true, online = false))
    }

    @Test
    fun `online failure shows dialog`() {
        assertTrue(
            gate(
                errorMessage = "音源解析失败（可能触发风控），请稍后再试",
                online = true,
            ),
        )
    }

    @Test
    fun `exhausted auto retry shows dialog even for non connectivity error`() {
        assertTrue(
            gate(
                errorMessage = "音源解析失败（可能触发风控），请稍后再试",
                online = false,
                autoRetryExhausted = true,
            ),
        )
    }

    @Test
    fun `no error means no dialog`() {
        assertFalse(gate(errorMessage = null, online = true, autoRetryExhausted = true))
    }
}
