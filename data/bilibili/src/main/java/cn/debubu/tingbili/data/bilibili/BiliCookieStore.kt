package cn.debubu.tingbili.data.bilibili

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B 站响应 Set-Cookie 回存（对标 PiliPlus 完整 CookieJar 的最小化实现）。
 *
 * 背景：B 站风控要求「会话连续性」——服务端会在响应里下发/刷新设备指纹 Cookie
 * （buvid3/buvid4 更新、b_nut、innersign 等），客户端必须保存并在后续请求回传，
 * 否则每次都是「无记忆」请求，容易被判定为脚本。
 *
 * 只收白名单内的设备级 Cookie（不碰登录态，登录态仍由 [CookieHeaderProvider] 管理）；
 * 进程内缓存即可，buvid3/4 的跨启动持久化在 [BuvidProvider]。
 *
 * 线程安全：OkHttp 拦截器多线程并发读写。
 */
@Singleton
class BiliCookieStore @Inject constructor() {

    private val map = ConcurrentHashMap<String, String>()

    /** 解析响应的 Set-Cookie 头列表，仅保留白名单字段 */
    fun onSetCookies(setCookieValues: List<String>) {
        for (raw in setCookieValues) {
            // "buvid3=xxx; Path=/; Domain=.bilibili.com; Expires=..." → 取首段 k=v
            val pair = raw.trim().split(';').firstOrNull()?.trim() ?: continue
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name in ALLOWLIST && value.isNotBlank()) {
                map[name] = value
            }
        }
    }

    /**
     * 把设备 Cookie 合并进手工 Cookie 头，优先级从高到低：
     * 1. [cookieHeader]（会话自带，尤其 Cookie 导入时账号绑定的 buvid3/4）；
     * 2. 响应 Set-Cookie 回存（服务端最新下发，如 spi 后刷新过的 buvid3）；
     * 3. [deviceCookie]（[BuvidProvider] 生成/持久化的本机 buvid）。
     *
     * 只补缺、不覆盖：会话自带的 buvid 一旦被设备值改写，playurl 会静默丢 Hi-Res/杜比
     * （2026-11 大会员账号实测）。
     *
     * [skip] 里的字段不参与合并（来自 2、3 的设备值）：登录态打 playurl 时必须跳过
     * [DEVICE_IDENTITY]，账号绑定的是浏览器那份，混入会被判 -351 或降级。
     */
    fun mergeInto(
        cookieHeader: String?,
        skip: Set<String> = emptySet(),
        deviceCookie: String? = null,
    ): String? {
        val pairs = LinkedHashMap<String, String>()

        fun absorb(header: String?) {
            header?.split(';')?.forEach { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@forEach
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) pairs.putIfAbsent(name, value)
            }
        }

        absorb(cookieHeader)
        absorb(deviceCookie)
        map.forEach { (name, value) -> if (name !in skip) pairs.putIfAbsent(name, value) }
        if (pairs.isEmpty()) return null
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    companion object {
        /** 设备身份 Cookie：登录态一律不发（账号绑定的是浏览器那份） */
        val DEVICE_IDENTITY = setOf("buvid3", "buvid4", "b_nut")

        /** 只回存设备指纹类 Cookie；登录态（SESSDATA/bili_jct 等）不在此管理 */
        val ALLOWLIST = DEVICE_IDENTITY + "innersign"
    }
}
