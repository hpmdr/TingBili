package cn.debubu.tingbili.data.bilibili

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用 bilibili-API-collect `docs/misc/sign/wbi.md` 的官方示例值做断言，
 * 保证签名算法与文档一致。
 */
class WbiSignerTest {

    // 不会被实际访问（纯计算测试用不到网络）
    private val signer = WbiSigner(navUrl = "http://127.0.0.1:1/nav")

    @Test
    fun `mixin key matches official example`() {
        val key = WbiSigner.getMixinKey(
            imgKey = "7cd084941338484aae1ad9425b84077c",
            subKey = "4932caff0ff746eab6f01bf08b70ac45",
        )
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
    }

    @Test
    fun `sign params matches official example`() {
        val signed = signer.signParams(
            params = mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"),
            wts = 1702204169L,
            mixinKey = "ea1db124af3c7062474693fa704f4ff8",
        )
        assertEquals("8f6f2b5b3d485fe1886cec6a0be8c5d4", signed["w_rid"])
        assertEquals("1702204169", signed["wts"])
    }

    @Test
    fun `encoding follows document rules`() {
        // 中文大写百分号编码、空格为 %20
        val encoded = mapOf("foo" to "one one four", "bar" to "五一四", "baz" to "1919810")
            .entries.sortedBy { it.key }
            .joinToString("&") { (k, v) ->
                "${WbiSigner.encodeURIComponent(k)}=${WbiSigner.encodeURIComponent(v)}"
            }
        assertEquals(
            "bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four",
            encoded,
        )
    }

    @Test
    fun `special chars filtered from signature but original params preserved`() {
        val signed = signer.signParams(
            params = mapOf("foo" to "1!14", "bar" to "5*14"),
            wts = 1702204169L,
            mixinKey = "ea1db124af3c7062474693fa704f4ff8",
        )
        // 原始参数原样保留用于发送；剔除只发生在签名计算内部（w_rid 由过滤后的参数算出）
        assertEquals("1!14", signed["foo"])
        assertEquals("5*14", signed["bar"])
        assertEquals("ed791ce4979dfe1e2aad3b03b73b13cc", signed["w_rid"])
    }
}
