package cn.debubu.tingbili.data.bilibili

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 提供 B 站风控所需的设备指纹 Cookie：buvid3/buvid4/b_nut。
 *
 * 生命周期（对标 PiliPlus AnonymousAccount + buvidActive）：
 * 1. 首次：匿名接口 `x/frontend/finger/spi` 取 buvid3/buvid4（缺它们 search 等接口易 412）；
 * 2. **激活**：调 `/x/internal/gaia-gateway/ExClimbWuzhi` 上报设备指纹（带 buvid3 Cookie）。
 *    只取不激活的 buvid 在风控眼里约等于没有，这是 PiliPlus 不撞 412 的关键一环；
 * 3. **持久化**：buvid 写 SharedPreferences，跨启动复用同一设备身份。
 *    每次冷启动换新 buvid 比不激活更可疑，持久化是激活的前提。
 *
 * 激活/获取失败都不阻塞：返回能拿到的最好 Cookie（失败时可能只有 buvid3+本地生成兜底）。
 */
// open 供单测替身覆盖 cookie()（线上走网络，单测不碰网）
open class BuvidProvider(
    context: Context? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val spiUrl: String = "https://api.bilibili.com/x/frontend/finger/spi",
    private val activateUrl: String = "https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi",
) {

    private val prefs = context?.getSharedPreferences("bili_buvid", Context.MODE_PRIVATE)

    /** 独立客户端，避免依赖带回包拦截器的共享 OkHttp */
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedCookie: String? = null

    private val lock = Any()

    /** 返回 "buvid3=...; buvid4=...; b_nut=..." 形式的 Cookie 值；获取失败返回 null */
    open fun cookie(): String? {
        cachedCookie?.let { return it }
        synchronized(lock) {
            cachedCookie?.let { return it }
            val cookie = loadPersisted() ?: fetch()
            if (cookie != null) cachedCookie = cookie
            return cookie
        }
    }

    private fun loadPersisted(): String? {
        val b3 = prefs?.getString(KEY_BUVID3, null) ?: return null
        if (b3.isBlank()) return null
        val b4 = prefs?.getString(KEY_BUVID4, null).orEmpty()
        // b_nut 是 buvid 的签发时刻，必须稳定；老版本没存过就只好以当前时间为准并补写
        val nut = prefs?.getLong(KEY_BNUT, 0L)?.takeIf { it > 0L } ?: nowSec().also {
            prefs?.edit()?.putLong(KEY_BNUT, it)?.apply()
        }
        return buildCookie(b3, b4, nut)
    }

    private fun fetch(): String? = try {
        val root = fetchSpi() ?: return null
        val b3 = root.getValue("b_3")
        val b4 = root.getValue("b_4")
        // 激活 best-effort：失败不影响本次返回（buvid 已持久化，下次启动同身份）
        try { activate(b3) } catch (_: Exception) {}
        val nut = nowSec()
        persist(b3, b4, nut)
        buildCookie(b3, b4, nut)
    } catch (_: Exception) {
        null
    }

    private fun buildCookie(b3: String, b4: String, bNutSec: Long): String {
        val sb = StringBuilder("buvid3=").append(b3)
        if (b4.isNotBlank()) sb.append("; buvid4=").append(b4)
        // b_nut = buvid 签发时刻（秒级时间戳），官方 Cookie 三件套之一；
        // 每次请求现算会被当成指纹抖动，必须与 buvid 一起持久化
        sb.append("; b_nut=").append(bNutSec)
        return sb.toString()
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun fetchSpi(): Map<String, String>? {
        val request = Request.Builder()
            .url(spiUrl)
            .header("User-Agent", WbiSigner.BROWSER_UA)
            .header("Referer", WbiSigner.REFERER)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
            val data = root["data"]?.jsonObject ?: return null
            val b3 = data["b_3"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
            val b4 = data["b_4"]?.jsonPrimitive?.content ?: ""
            return mapOf("b_3" to b3, "b_4" to b4)
        }
    }

    /**
     * 设备指纹激活（PiliPlus 同款 payload）：随机 PNG 结尾特征 + gaia 风控来源标记。
     * 请求必须带 buvid3 Cookie，服务端把指纹与设备身份绑定。
     */
    private fun activate(buvid3: String) {
        val random = SecureRandom()
        // 随机 PNG 结尾特征（对标 PiliPlus）：32 随机 + 4×0 + "IEND" + 4 随机 → base64 取末 50 字符
        val arr = ByteArray(44)
        random.nextBytes(arr)
        for (i in 32..35) arr[i] = 0
        arr[36] = 73; arr[37] = 69; arr[38] = 78; arr[39] = 68
        val tail = ByteArray(4)
        random.nextBytes(tail)
        tail.copyInto(arr, 40)
        val b64 = Base64.getEncoder().encodeToString(arr)
        val pngTail = b64.substring((b64.length - 50).coerceAtLeast(0))

        val payload = buildString {
            append("{\"3064\":1,\"39c8\":\"333.1387.fp.risk\",\"3c43\":{\"adca\":\"Linux\",\"bfe9\":\"")
            append(pngTail)
            append("\"}}")
        }
        val body = kotlinx.serialization.json.buildJsonObject {
            // payload 本身是一段 JSON 文本，按字符串放进外层对象（PiliPlus 同款）
            put("payload", kotlinx.serialization.json.JsonPrimitive(payload))
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(activateUrl)
            .header("User-Agent", WbiSigner.BROWSER_UA)
            .header("Referer", WbiSigner.REFERER)
            .header("Cookie", "buvid3=$buvid3")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            // 非 2xx/解析失败都算激活失败，静默（调用方已 try 吞掉）
            if (!resp.isSuccessful) return
        }
    }

    private fun persist(b3: String, b4: String, bNutSec: Long) {
        prefs?.edit()
            ?.putString(KEY_BUVID3, b3)
            ?.putString(KEY_BUVID4, b4)
            ?.putLong(KEY_BNUT, bNutSec)
            ?.apply()
    }

    companion object {
        private const val KEY_BUVID3 = "buvid3"
        private const val KEY_BUVID4 = "buvid4"
        private const val KEY_BNUT = "b_nut"
    }
}
