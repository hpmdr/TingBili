package cn.debubu.tingbili.data.bilibili.auth

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Web 扫码登录状态机（对标 PiliPlus TV 码轮询语义，接口换成 Web 侧无需 app 签名） */
sealed interface QrStatus {
    /** 86101：未扫码 */
    data object Waiting : QrStatus
    /** 86090：已扫码未确认 */
    data object Scanned : QrStatus
    /** 86038：二维码过期 */
    data object Expired : QrStatus
    /** 0：扫码成功，带换 Cookie 的跳转地址 */
    data class Success(val redirectUrl: String, val refreshToken: String) : QrStatus
    data class Failed(val message: String) : QrStatus
}

data class QrData(val qrcodeKey: String, val url: String)

data class NavProfile(val uid: Long, val userName: String, val face: String, val isLogin: Boolean)

/**
 * B 站 Web 登录仓储：扫码二维码生成/轮询、跳转换 Cookie、nav 验态、Cookie 粘贴、退出。
 * 自带独立 OkHttp（不走共享 WbiInterceptor，避免递归与陈旧 Cookie 干扰）。
 */
class AuthRepository @Inject constructor(
    private val json: Json,
    private val store: AuthStore,
) {
    internal var passportBase: String = "https://passport.bilibili.com"
    internal var apiBase: String = "https://api.bilibili.com"

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _sessionFlow = MutableStateFlow(store.load())
    val sessionFlow: StateFlow<BiliSession?> = _sessionFlow.asStateFlow()

    // ── 扫码 ─────────────────────────────────────────────

    suspend fun generateQr(): Result<QrData> = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$passportBase/x/passport-login/web/qrcode/generate")
            val dto = json.decodeFromString(QrGenerateDto.serializer(), body)
            require(dto.code == 0 && dto.data != null) { dto.message.ifBlank { "qrcode generate failed: ${dto.code}" } }
            QrData(dto.data.qrcodeKey, dto.data.url)
        }
    }

    suspend fun pollQr(qrcodeKey: String): QrStatus = withContext(Dispatchers.IO) {
        try {
            val body = get("$passportBase/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey")
            val dto = json.decodeFromString(QrPollDto.serializer(), body)
            when (dto.code) {
                0 -> {
                    val url = dto.data?.url.orEmpty()
                    if (url.isBlank()) QrStatus.Failed("empty redirect url")
                    else QrStatus.Success(url, dto.data?.refreshToken.orEmpty())
                }
                86101 -> QrStatus.Waiting
                86090 -> QrStatus.Scanned
                86038 -> QrStatus.Expired
                else -> QrStatus.Failed(dto.message.ifBlank { "poll failed: ${dto.code}" })
            }
        } catch (e: Exception) {
            QrStatus.Failed(e.message ?: "poll error")
        }
    }

    /**
     * 带着跳转地址逐跳跟随（最多 5 跳），收集整条链的 Set-Cookie。
     * 扫码成功与 Cookie 粘贴后都要经 nav 验证才落盘。
     */
    suspend fun exchangeCookies(redirectUrl: String): Map<String, String> = withContext(Dispatchers.IO) {
        val cookies = mutableMapOf<String, String>()
        var url = redirectUrl
        repeat(MAX_REDIRECT_HOPS) {
            val resp = getResponse(url)
            try {
                resp.headers("Set-Cookie").forEach { header ->
                    val pair = header.substringBefore(";")
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=", "").trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
                }
                val next = resp.header("Location")?.takeIf { it.isNotBlank() } ?: return@withContext cookies
                url = next
                if (!next.startsWith("http")) return@withContext cookies
            } finally {
                resp.close()
            }
        }
        cookies
    }

    suspend fun fetchNav(cookieHeader: String): Result<NavProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$apiBase/x/web-interface/nav", cookieHeader)
            val dto = json.decodeFromString(NavDto.serializer(), body)
            require(dto.code == 0 && dto.data != null) { dto.message.ifBlank { "nav failed: ${dto.code}" } }
            val d = dto.data
            NavProfile(d.mid, d.uname.orEmpty(), d.face.orEmpty(), d.isLogin)
        }
    }

    /** 扫码成功后调用：换 Cookie → nav 验证 → 落盘并发布 */
    suspend fun finishQrLogin(redirectUrl: String): Result<BiliSession> {
        val cookies = try {
            exchangeCookies(redirectUrl)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val sessdata = cookies["SESSDATA"].orEmpty()
        val uid = cookies["DedeUserID"]?.toLongOrNull() ?: 0L
        if (sessdata.isBlank() || uid <= 0L) return Result.failure(IllegalStateException("未换到有效 Cookie"))
        val header = provisionalHeader(cookies)
        val nav = fetchNav(header).getOrElse { return Result.failure(it) }
        if (!nav.isLogin) return Result.failure(IllegalStateException("Cookie 无效（nav 未登录）"))
        val session = BiliSession(
            sessdata = sessdata,
            biliJct = cookies["bili_jct"].orEmpty(),
            uid = uid,
            userName = nav.userName,
            face = nav.face,
            extras = cookies.filterKeys { it == "DedeUserID__ckMd5" || it == "sid" },
        )
        save(session)
        return Result.success(session)
    }

    /** Cookie 粘贴登录：解析 → nav 验证 → 落盘 */
    suspend fun loginWithCookies(raw: String): Result<BiliSession> {
        val parsed = BiliSession.parseCookieHeader(raw)
            ?: return Result.failure(IllegalArgumentException("没找到 SESSDATA / DedeUserID，请粘贴完整 Cookie"))
        val nav = fetchNav(parsed.toCookieHeader()).getOrElse { return Result.failure(it) }
        if (!nav.isLogin || (nav.uid != 0L && nav.uid != parsed.uid)) {
            return Result.failure(IllegalStateException("Cookie 无效或已过期"))
        }
        val session = parsed.copy(
            userName = nav.userName.ifBlank { parsed.userName },
            face = nav.face.ifBlank { parsed.face },
            uid = if (nav.uid != 0L) nav.uid else parsed.uid,
        )
        save(session)
        return Result.success(session)
    }

    suspend fun logout() {
        val csrf = _sessionFlow.value?.biliJct.orEmpty()
        try {
            if (csrf.isNotBlank()) {
                post(
                    "$passportBase/login/exit/v2",
                    "biliCSRF=$csrf",
                    _sessionFlow.value?.toCookieHeader(),
                )
            }
        } catch (_: Exception) {
            // 服务端退出尽力而为，本地必清
        }
        save(null)
    }

    fun save(session: BiliSession?) {
        store.save(session)
        _sessionFlow.value = session
    }

    // ── 内部 ─────────────────────────────────────────────

    private fun provisionalHeader(cookies: Map<String, String>): String = buildString {
        append("SESSDATA=").append(cookies["SESSDATA"])
        cookies["bili_jct"]?.takeIf { it.isNotBlank() }?.let { append("; bili_jct=").append(it) }
        cookies["DedeUserID"]?.let { append("; DedeUserID=").append(it) }
    }

    private fun baseHeaders(cookieHeader: String? = null): Request.Builder =
        Request.Builder()
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://www.bilibili.com")
            .apply { cookieHeader?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }

    private fun get(url: String, cookieHeader: String? = null): String {
        val resp = getResponse(url, cookieHeader)
        resp.use {
            require(it.isSuccessful) { "http ${it.code}" }
            return it.body.string()
        }
    }

    private fun getResponse(url: String, cookieHeader: String? = null) =
        client.newCall(baseHeaders(cookieHeader).url(url).get().build()).execute()

    private fun post(url: String, formBody: String, cookieHeader: String? = null): String {
        val body = okhttp3.FormBody.Builder().apply {
            formBody.split("&").forEach {
                val k = it.substringBefore("=")
                val v = it.substringAfter("=", "")
                add(k, v)
            }
        }.build()
        client.newCall(baseHeaders(cookieHeader).url(url).post(body).build()).execute().use {
            return it.body.string()
        }
    }

    @Serializable
    private data class QrGenerateDto(val code: Int = -1, val message: String = "", val data: QrGenerateData? = null)

    @Serializable
    private data class QrGenerateData(val url: String = "", @SerialName("qrcode_key") val qrcodeKey: String = "")

    @Serializable
    private data class QrPollDto(val code: Int = -1, val message: String = "", val data: QrPollData? = null)

    @Serializable
    private data class QrPollData(val url: String = "", @SerialName("refresh_token") val refreshToken: String = "", val timestamp: Long = 0L)

    @Serializable
    private data class NavDto(val code: Int = -1, val message: String = "", val data: NavData? = null)

    @Serializable
    private data class NavData(
        val isLogin: Boolean = false,
        val mid: Long = 0L,
        val uname: String = "",
        val face: String = "",
    )

    companion object {
        const val MAX_REDIRECT_HOPS = 5
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; 2311DRK48C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
