package cn.debubu.tingbili.data.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WBI 签名实现，对照 bilibili-API-collect `docs/misc/sign/wbi.md`。
 *
 * 流程：nav 接口取 img_key/sub_key（匿名可用）→ 重排得 mixin_key（缓存 30 分钟，
 * 官方口径每日更替）→ 参数过滤 + 加 wts + 键名升序 + 百分号编码 → MD5 得 w_rid。
 *
 * 纯计算部分 [getMixinKey] / [signParams] 无网络依赖，可直接单测；
 * [signedEncodedQuery] 供 [WbiInterceptor] 重建请求 query。
 */
class WbiSigner(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val navUrl: String = "https://api.bilibili.com/x/web-interface/nav",
    private val keyTtlMs: Long = 30 * 60 * 1000L,
) {

    /** 独立客户端，避免走带回包拦截器的共享 OkHttp 造成递归 */
    private val navClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedMixinKey: String? = null

    @Volatile
    private var fetchedAtMs: Long = 0L

    private val lock = Any()

    /** 返回可直接替换原 query 的完整编码串（原参数顺序 + w_rid + wts）；拿不到 key 时返回 null，调用方应放行原请求 */
    fun signedEncodedQuery(params: Map<String, String>): String? {
        val mixinKey = mixinKey() ?: return null
        val wts = System.currentTimeMillis() / 1000
        val signed = signParams(params, wts, mixinKey)
        val original = params.entries.joinToString("&") { (k, v) ->
            "${encodeURIComponent(k)}=${encodeURIComponent(v)}"
        }
        val suffix = "w_rid=${signed.getValue("w_rid")}&wts=$wts"
        return if (original.isEmpty()) suffix else "$original&$suffix"
    }

    /** 拿不到 key 时返回 null（首次未缓存则同步请求 nav，调用方已在 OkHttp IO 线程） */
    fun mixinKey(): String? {
        cachedMixinKey?.let { key ->
            if (System.currentTimeMillis() - fetchedAtMs < keyTtlMs) return key
        }
        synchronized(lock) {
            cachedMixinKey?.let { key ->
                if (System.currentTimeMillis() - fetchedAtMs < keyTtlMs) return key
            }
            val key = fetchMixinKey()
            if (key != null) {
                cachedMixinKey = key
                fetchedAtMs = System.currentTimeMillis()
            }
            return key
        }
    }

    private fun fetchMixinKey(): String? = try {
        val request = Request.Builder()
            .url(navUrl)
            .header("User-Agent", BROWSER_UA)
            .build()
        navClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
            // 匿名时 code=-101，但 data.wbi_img 仍然返回
            val wbi = root["data"]?.jsonObject?.get("wbi_img")?.jsonObject ?: return null
            val imgKey = wbi["img_url"]?.jsonPrimitive?.content
                ?.substringAfterLast('/')?.substringBefore('.') ?: return null
            val subKey = wbi["sub_url"]?.jsonPrimitive?.content
                ?.substringAfterLast('/')?.substringBefore('.') ?: return null
            getMixinKey(imgKey, subKey)
        }
    } catch (_: Exception) {
        null
    }

    fun signParams(
        params: Map<String, String>,
        wts: Long,
        mixinKey: String,
    ): Map<String, String> {
        val filtered = params.entries
            .filter { (k, _) -> k.none { it in FILTER_CHARS } }
            .associate { (k, v) -> k to v.filter { it !in FILTER_CHARS } }
            .plus("wts" to wts.toString())
        val sortedQuery = filtered.toSortedMap().entries.joinToString("&") { (k, v) ->
            "${encodeURIComponent(k)}=${encodeURIComponent(v)}"
        }
        val wRid = md5Hex(sortedQuery + mixinKey)
        return params.plus(mapOf("wts" to wts.toString(), "w_rid" to wRid))
    }

    companion object {
        /** 需要从参数中剔除的字符（文档规定） */
        private const val FILTER_CHARS = "!'()*"

        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val REFERER = "https://www.bilibili.com/"

        /** 官方重排映射表 */
        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52,
        )

        fun getMixinKey(imgKey: String, subKey: String): String {
            val raw = imgKey + subKey
            return MIXIN_KEY_ENC_TAB.take(32).map { raw[it] }.joinToString("")
        }

        /** 与 JS encodeURIComponent 对齐：保留 A-Za-z0-9-_.~，空格为 %20，十六进制大写 */
        internal fun encodeURIComponent(s: String): String {
            val sb = StringBuilder(s.length)
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
                ) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append(String.format("%02X", c))
                }
            }
            return sb.toString()
        }

        private fun md5Hex(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { String.format("%02x", it) }
    }
}
