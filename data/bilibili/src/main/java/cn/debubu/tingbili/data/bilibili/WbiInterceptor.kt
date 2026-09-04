package cn.debubu.tingbili.data.bilibili

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 通用请求拦截器：
 * - 所有请求补浏览器 UA + Referer（B 站 CDN 与风控的基本要求）
 * - 登录后经 [cookieProvider] 注入 Cookie（游客模式为 null）
 * - 对需要 WBI 签名的路径（/search/、/wbi/）用 [WbiSigner] 重建 query 加上 w_rid/wts；
 *   签名不可用时放行原请求，不阻塞调用方
 */
class WbiInterceptor(
    private val wbiSigner: WbiSigner,
    private val cookieProvider: () -> String? = { null },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("User-Agent", WbiSigner.BROWSER_UA)
            .header("Referer", WbiSigner.REFERER)

        cookieProvider()?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

        val url = original.url
        if (needsWbiSign(url)) {
            // HttpUrl.queryParameter* 返回解码后的值，重签后再整体编码，保证与服务端校验一致
            val params = LinkedHashMap<String, String>()
            for (name in url.queryParameterNames) {
                url.queryParameterValues(name).forEach { v ->
                    if (v != null) params[name] = v
                }
            }
            val signedQuery = wbiSigner.signedEncodedQuery(params)
            if (!signedQuery.isNullOrBlank()) {
                builder.url(url.newBuilder().query(null).encodedQuery(signedQuery).build())
            }
        }

        return chain.proceed(builder.build())
    }

    private fun needsWbiSign(url: HttpUrl): Boolean =
        url.encodedPath.contains("/search/") || url.encodedPath.contains("/wbi/")
}
