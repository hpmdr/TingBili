package cn.debubu.tingbili.data.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 提供 B 站风控所需的 buvid3/buvid4 Cookie。
 *
 * 背景：B 站 web 接口（尤其 search 等 WBI 签名接口）在请求缺少 buvid3 Cookie 时，
 * 风控会随机返回 HTTP 412。通过匿名接口 `x/frontend/finger/spi` 即可获取 buvid3/buvid4。
 *
 * 进程内缓存；获取失败返回 null（不注入 Cookie，不阻塞请求）。
 */
class BuvidProvider(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val spiUrl: String = "https://api.bilibili.com/x/frontend/finger/spi",
) {

    /** 独立客户端，避免依赖带回包拦截器的共享 OkHttp */
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedCookie: String? = null

    private val lock = Any()

    /** 返回 "buvid3=...; buvid4=..." 形式的 Cookie 值；获取失败返回 null */
    fun cookie(): String? {
        cachedCookie?.let { return it }
        synchronized(lock) {
            cachedCookie?.let { return it }
            val cookie = fetch()
            if (cookie != null) cachedCookie = cookie
            return cookie
        }
    }

    private fun fetch(): String? = try {
        val request = Request.Builder()
            .url(spiUrl)
            .header("User-Agent", WbiSigner.BROWSER_UA)
            .header("Referer", WbiSigner.REFERER)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
            val data = root["data"]?.jsonObject ?: return null
            val buvid3 = data["b_3"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
            val buvid4 = data["b_4"]?.jsonPrimitive?.content ?: ""
            buildString {
                append("buvid3=").append(buvid3)
                if (buvid4.isNotBlank()) append("; buvid4=").append(buvid4)
            }
        }
    } catch (_: Exception) {
        null
    }
}
