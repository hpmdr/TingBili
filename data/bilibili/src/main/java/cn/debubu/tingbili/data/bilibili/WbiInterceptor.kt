package cn.debubu.tingbili.data.bilibili

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 通用请求拦截器：
 * - 所有请求补浏览器 UA + Referer（B 站 CDN 与风控的基本要求）
 * - 登录后经 [cookieProvider] 注入 Cookie（游客模式为 null）；
 *   带 [GUEST_HEADER]=true 的请求强制走游客身份（风控兜底，防止登录态被限流时整条播放瘫痪）
 * - [cookieStore] 回存的设备 Cookie（buvid3/4、b_nut、innersign）合并进 Cookie 头，
 *   服务端下发的最新值优先；游客请求也带设备 Cookie（游客搜索没有 buvid 必 412）
 * - 响应里的 Set-Cookie 捕获回存（对标 PiliPlus 完整 CookieJar 的最小化版本，
 *   B 站风控要求会话连续性）
 * - 对需要 WBI 签名的路径（/search/、/wbi/）用 [WbiSigner] 重建 query 加上 w_rid/wts；
 *   签名不可用时放行原请求，不阻塞调用方
 * - web 搜索请求补齐浏览器特征：platform/page_size/web_location 参数 +
 *   search.bilibili.com 的 Referer/Origin（对标 PiliPlus searchByType）
 */
class WbiInterceptor(
    private val wbiSigner: WbiSigner,
    private val cookieProvider: () -> String? = { null },
    private val cookieStore: BiliCookieStore = BiliCookieStore(),
    /** [BuvidProvider.cookie]：本机持久化的 buvid3/4/b_nut，搜索等接口缺它必被软拦 */
    private val deviceCookieProvider: () -> String? = { null },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val guest = original.header(GUEST_HEADER)?.toBooleanStrictOrNull() == true
        val builder = original.newBuilder()
            .header("User-Agent", WbiSigner.BROWSER_UA)
            .header("Referer", WbiSigner.REFERER)
            .removeHeader(GUEST_HEADER)

        // 游客重试：跳过登录 Cookie，避免被账号级 412 连坐（设备 Cookie 仍然保留）
        val sessionCookie = if (!guest) cookieProvider()?.takeIf { it.isNotBlank() } else null
        sessionCookie?.let { builder.header("Cookie", it) }
        // 设备 Cookie 合并：只补缺、不覆盖会话自带值（BiliCookieStore.mergeInto 内保证）。
        // 例外：登录态且会话没有自带 buvid 时，playurl 不发设备 buvid——
        // 实测账号绑的是浏览器那份，混入设备 buvid 会让 dash.audio 静默降级（Hi-Res/杜比消失）。
        // 反过来 search 必须带 buvid：不带时服务端 code=0 但返回 v_voucher + 空结果（风控软拦）。
        val allowDeviceCookie = sessionCookie == null ||
            (sessionCookie.contains("buvid3=") == false && !isPlayUrlPath(original.url))
        cookieStore.mergeInto(
            cookieHeader = sessionCookie,
            skip = if (allowDeviceCookie) emptySet() else BiliCookieStore.DEVICE_IDENTITY,
            deviceCookie = if (allowDeviceCookie) deviceCookieProvider() else null,
        )?.let {
            builder.header("Cookie", it)
        }

        val url = original.url
        val isWebSearch = url.encodedPath.startsWith(WEB_SEARCH_PATH)
        if (isWebSearch) {
            // 搜索页浏览器特征：真实浏览器搜索时 Referer/Origin 都在 search 子域
            builder
                .header("Referer", SEARCH_REFERER)
                .header("Origin", SEARCH_ORIGIN)
        }
        if (needsWbiSign(url)) {
            // HttpUrl.queryParameter* 返回解码后的值，重签后再整体编码，保证与服务端校验一致
            val params = LinkedHashMap<String, String>()
            for (name in url.queryParameterNames) {
                url.queryParameterValues(name).forEach { v ->
                    if (v != null) params[name] = v
                }
            }
            if (isWebSearch) {
                // PiliPlus searchByType 同款参数：缺了就是「非浏览器请求」的显性特征
                params.putIfAbsent("platform", "pc")
                params.putIfAbsent("page_size", "20")
                params.putIfAbsent("web_location", SEARCH_WEB_LOCATION)
            }
            val signedQuery = wbiSigner.signedEncodedQuery(params)
            if (!signedQuery.isNullOrBlank()) {
                builder.url(url.newBuilder().query(null).encodedQuery(signedQuery).build())
            }
        }

        val response = chain.proceed(builder.build())
        // Set-Cookie 回存：设备指纹 Cookie 跨请求保持连续
        cookieStore.onSetCookies(response.headers("Set-Cookie"))
        return response
    }

    private fun needsWbiSign(url: HttpUrl): Boolean =
        url.encodedPath.contains("/search/") || url.encodedPath.contains("/wbi/") || url.encodedPath.contains("/history/toview/web")

    /** playurl（web/wbi 两种路径）：音质对 buvid 归属敏感，见上面的合并策略 */
    private fun isPlayUrlPath(url: HttpUrl): Boolean =
        url.encodedPath == "/x/player/playurl" || url.encodedPath == "/x/player/wbi/playurl"

    companion object {
        /** 内部标记头：只在本进程内传给拦截器，发出前一定会被移除 */
        const val GUEST_HEADER = "X-TingBili-Guest"

        private const val WEB_SEARCH_PATH = "/x/web-interface/search"
        private const val SEARCH_REFERER = "https://search.bilibili.com/"
        private const val SEARCH_ORIGIN = "https://search.bilibili.com"
        private const val SEARCH_WEB_LOCATION = "1430654"
    }
}
