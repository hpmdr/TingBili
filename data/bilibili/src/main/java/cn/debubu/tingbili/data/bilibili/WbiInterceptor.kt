package cn.debubu.tingbili.data.bilibili

import okhttp3.Interceptor
import okhttp3.Response

/**
 * WbiInterceptor stub — transparent pass-through with pre-reserved Cookie injection.
 *
 * Visitor mode: no Cookie, request proceeds as-is.
 * Logged-in mode (future): [cookieProvider] returns SESSDATA string from AuthRepository.getCookie().
 *
 * WBI signature (w_rid/wts) is isolated here; future implementation will sign
 * query params using mixin key without touching callers.
 */
class WbiInterceptor(
    private val cookieProvider: () -> String? = { null }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
        val cookie = cookieProvider()
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }
        // TODO: WBI signing — compute w_rid from params + mixin key, append wts/w_rid
        return chain.proceed(builder.build())
    }
}
