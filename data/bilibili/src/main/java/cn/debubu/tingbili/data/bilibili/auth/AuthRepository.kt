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
open class AuthRepository @Inject constructor(
    private val json: Json,
    private val store: AuthStore,
) {
    internal var passportBase: String = "https://passport.bilibili.com"
    internal var apiBase: String = "https://api.bilibili.com"
    internal var correspondBase: String = "https://www.bilibili.com"

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _sessionFlow = MutableStateFlow(store.load())
    val sessionFlow: StateFlow<BiliSession?> = _sessionFlow.asStateFlow()

    // ── 扫码 ─────────────────────────────────────────────

    open suspend fun generateQr(): Result<QrData> = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$passportBase/x/passport-login/web/qrcode/generate")
            val dto = json.decodeFromString(QrGenerateDto.serializer(), body)
            require(dto.code == 0 && dto.data != null) { dto.message.ifBlank { "qrcode generate failed: ${dto.code}" } }
            QrData(dto.data.qrcodeKey, dto.data.url)
        }
    }

    open suspend fun pollQr(qrcodeKey: String): QrStatus = withContext(Dispatchers.IO) {
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

    /** 扫码成功后调用：换 Cookie → nav 验证 → 落盘并发布（含 refresh_token，续期用） */
    open suspend fun finishWebLogin(redirectUrl: String, refreshToken: String = ""): Result<BiliSession> {
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
            refreshToken = refreshToken,
            extras = cookies.filterKeys { it == "DedeUserID__ckMd5" || it == "sid" },
        )
        save(session)
        return Result.success(session)
    }

    /** Cookie 粘贴登录：解析 → nav 验证 → 落盘 */
    open suspend fun loginWithCookies(raw: String): Result<BiliSession> {
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

    /**
     * 会话保活（PiliPlus 稳定的内核，Web 侧对等协议）：
     * cookie/info 问是否需要续期 → correspond 拿 refresh_csrf → cookie/refresh
     * 换新 Cookie → confirm 退役旧 token → nav 验证 → 落盘。
     * 无会话 / 无 refresh_token（老会话）/ 网络失败一律返回 false，调用方忽略即可。
     * 进程启动时调一次（MainViewModel），每天自然触发，不额外建定时任务。
     */
    suspend fun maintainSession(): Boolean {
        val current = _sessionFlow.value ?: return false
        if (current.refreshToken.isBlank()) return false
        return try {
            refreshSession(current)?.let { save(it); true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun refreshSession(current: BiliSession): BiliSession? = withContext(Dispatchers.IO) {
        if (!needsRefresh(current)) return@withContext current
        val csrf = current.biliJct
        if (csrf.isBlank()) return@withContext null
        val refreshCsrf = fetchRefreshCsrf(csrf) ?: return@withContext null
        val form = "csrf=$csrf&refresh_csrf=$refreshCsrf&source=main_web&refresh_token=${current.refreshToken}"
        val resp = postResponse("$passportBase/x/passport-login/web/cookie/refresh", form, current.toCookieHeader())
        resp.use {
            if (!it.isSuccessful) return@withContext null
            val body = it.body.string()
            val dto = json.decodeFromString(CookieRefreshDto.serializer(), body)
            if (dto.code != 0) return@withContext null
            val cookies = mutableMapOf<String, String>()
            it.headers("Set-Cookie").forEach { header ->
                val name = header.substringBefore(";").substringBefore("=").trim()
                val value = header.substringAfter("=", "").substringBefore(";").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
            val sessdata = cookies["SESSDATA"].orEmpty()
            val uid = cookies["DedeUserID"]?.toLongOrNull() ?: 0L
            if (sessdata.isBlank() || uid <= 0L) return@withContext null
            // 新凭证必须验过才落盘
            val header = provisionalHeader(cookies)
            val nav = fetchNav(header).getOrNull()?.takeIf { n -> n.isLogin } ?: return@withContext null
            confirmRefresh(csrf, current.refreshToken, current.toCookieHeader())
            BiliSession(
                sessdata = sessdata,
                biliJct = cookies["bili_jct"].orEmpty(),
                uid = uid,
                userName = nav.userName.ifBlank { current.userName },
                face = nav.face.ifBlank { current.face },
                refreshToken = dto.data?.refreshToken.orEmpty().ifBlank { current.refreshToken },
                extras = cookies.filterKeys { k -> k == "DedeUserID__ckMd5" || k == "sid" },
            )
        }
    }

    private fun needsRefresh(current: BiliSession): Boolean {
        val body = get("$passportBase/x/passport-login/web/cookie/info", current.toCookieHeader())
        val dto = json.decodeFromString(CookieInfoDto.serializer(), body)
        return dto.code == 0 && (dto.data?.refresh == true)
    }

    private fun fetchRefreshCsrf(csrf: String): String? {
        val html = get("$correspondBase/correspond/1/$csrf", null)
        return REFRESH_CSRF_RE.find(html)?.groupValues?.getOrNull(1)
    }

    private fun confirmRefresh(csrf: String, oldToken: String, cookieHeader: String) {
        try {
            post("$passportBase/x/passport-login/web/confirm/refresh", "csrf=$csrf&refresh_token=$oldToken", cookieHeader)
        } catch (_: Exception) {
            // 旧 token 退役尽力而为
        }
    }

    // ── 短信登录（Web，参数形态对标 biu，同 PiliPlus 规则：60s 冷却、验证码 5 分钟有效） ──

    /** 极验票据：先拿 token/gt/challenge，滑完得 validate/seccode，再发短信/密码 */
    data class GeeTicket(val token: String, val gt: String, val challenge: String)

    data class GeeResult(val validate: String, val seccode: String, val challenge: String)

    suspend fun fetchGeeTicket(): Result<GeeTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$passportBase/x/passport-login/captcha?source=main_web")
            val dto = json.decodeFromString(CaptchaDto.serializer(), body)
            require(dto.code == 0 && dto.data != null) { dto.message.ifBlank { "captcha failed: ${dto.code}" } }
            val g = dto.data.geetest ?: throw IllegalStateException("captcha missing geetest")
            GeeTicket(dto.data.token, g.gt, g.challenge)
        }
    }

    suspend fun sendSms(
        cid: Int, tel: String, ticket: GeeTicket, result: GeeResult,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val form = "cid=$cid&tel=$tel&source=main_web" +
                "&token=${ticket.token}&challenge=${result.challenge}" +
                "&validate=${result.validate}&seccode=${result.seccode}"
            val body = post("$passportBase/x/passport-login/web/sms/send", form, null)
            val dto = json.decodeFromString(SmsSendDto.serializer(), body)
            require(dto.code == 0 && dto.data != null) { dto.message.ifBlank { "sms send failed: ${dto.code}" } }
            val key = dto.data.captchaKey
            require(key.isNotBlank()) { "sms send missing captcha_key" }
            key
        }
    }

    /**
     * 短信登录：成功返回 despite code==0；data.status==2 表示撞风控（需二次验证，
     * 该链路最后一步 oauth 要 app 签名，Web 路径走不通，直接报 message 让用户换扫码/Cookie）。
     */
    suspend fun smsLogin(cid: Int, tel: String, code: String, captchaKey: String): Result<BiliSession> {
        val body = try {
            val form = "cid=$cid&tel=$tel&code=$code&source=main_web&captcha_key=$captchaKey&keep=true"
            post("$passportBase/x/passport-login/web/login/sms", form, null)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return completePasswordLikeLogin(body)
    }

    // ── 密码登录（Web：web/key 取 RSA 公钥加密 hash+密码，极验票据必填，同 biu） ──

    suspend fun fetchWebKey(): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = get("$passportBase/x/passport-login/web/key")
            val dto = json.decodeFromString(WebKeyDto.serializer(), body)
            require(dto.code == 0 && dto.data != null) { dto.message.ifBlank { "web key failed: ${dto.code}" } }
            val hash = dto.data.hash
            val key = dto.data.key
            require(hash.isNotBlank() && key.isNotBlank()) { "web key empty" }
            hash to key
        }
    }

    /** RSA/ECB/PKCS1Padding 加密（对标 JSEncrypt 默认配置），明文 = hash + 密码 */
    fun rsaEncrypt(pemPublicKey: String, plain: String): String {
        val pem = pemPublicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val der = java.util.Base64.getMimeDecoder().decode(pem)
        val keySpec = java.security.spec.X509EncodedKeySpec(der)
        val key = java.security.KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(encrypted)
    }

    suspend fun passwordLogin(username: String, password: String, ticket: GeeTicket, result: GeeResult): Result<BiliSession> {
        val (hash, key) = fetchWebKey().getOrElse { return Result.failure(it) }
        val encrypted = try {
            rsaEncrypt(key, hash + password)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("密码加密失败，请重试"))
        }
        val body = try {
            val form = "username=$username&password=$encrypted" +
                "&keep=0&token=${ticket.token}&challenge=${result.challenge}" +
                "&validate=${result.validate}&seccode=${result.seccode}&source=main_web"
            post("$passportBase/x/passport-login/web/login", form, null)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return completePasswordLikeLogin(body)
    }

    /** 短信/密码登录共用收尾：code==0 且 status==0/缺省 → url 换 Cookie；status==2 风控直接报错 */
    private suspend fun completePasswordLikeLogin(body: String): Result<BiliSession> {
        val dto = try {
            json.decodeFromString(PasswordLoginDto.serializer(), body)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (dto.code != 0) return Result.failure(IllegalStateException(dto.message.ifBlank { "login failed: ${dto.code}" }))
        val d = dto.data ?: return Result.failure(IllegalStateException("login empty data"))
        if ((d.status ?: 0) == 2) {
            return Result.failure(
                IllegalStateException(d.message.orEmpty().ifBlank { "本次登录环境存在风险，需验证手机号" } + "，请改用扫码或 Cookie 登录")
            )
        }
        val url = d.url.orEmpty()
        if (url.isBlank()) return Result.failure(IllegalStateException("login missing redirect url"))
        return finishWebLogin(url, d.refreshToken.orEmpty())
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

    private fun postResponse(url: String, formBody: String, cookieHeader: String? = null) =
        client.newCall(
            baseHeaders(cookieHeader).url(url).post(formBody.toFormBody()).build()
        ).execute()

    private fun String.toFormBody(): okhttp3.RequestBody =
        okhttp3.FormBody.Builder().apply {
            split("&").forEach {
                add(it.substringBefore("="), it.substringAfter("=", ""))
            }
        }.build()

    private fun post(url: String, formBody: String, cookieHeader: String? = null): String {
        postResponse(url, formBody, cookieHeader).use { return it.body.string() }
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
    private data class CookieInfoDto(val code: Int = -1, val data: CookieInfoData? = null)

    @Serializable
    private data class CookieInfoData(val refresh: Boolean = false, val timestamp: Long = 0L)

    @Serializable
    private data class CookieRefreshDto(val code: Int = -1, val message: String = "", val data: CookieRefreshData? = null)

    @Serializable
    private data class CookieRefreshData(@SerialName("refresh_token") val refreshToken: String = "")

    @Serializable
    private data class CaptchaDto(val code: Int = -1, val message: String = "", val data: CaptchaData? = null)

    @Serializable
    private data class CaptchaData(
        val type: String = "",
        val token: String = "",
        val geetest: GeeTestInfo? = null,
    )

    @Serializable
    private data class GeeTestInfo(val gt: String = "", val challenge: String = "")

    @Serializable
    private data class SmsSendDto(val code: Int = -1, val message: String = "", val data: SmsSendData? = null)

    @Serializable
    private data class SmsSendData(@SerialName("captcha_key") val captchaKey: String = "")

    @Serializable
    private data class PasswordLoginDto(val code: Int = -1, val message: String = "", val data: PasswordLoginData? = null)

    @Serializable
    private data class PasswordLoginData(
        val status: Int? = null,
        val url: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class WebKeyDto(val code: Int = -1, val message: String = "", val data: WebKeyData? = null)

    @Serializable
    private data class WebKeyData(val hash: String = "", val key: String = "")

    @Serializable
    private data class NavData(
        val isLogin: Boolean = false,
        val mid: Long = 0L,
        val uname: String = "",
        val face: String = "",
    )

    companion object {
        val REFRESH_CSRF_RE = Regex(""""refresh_token"\s*:\s*"([^"]+)"""")
        const val MAX_REDIRECT_HOPS = 5
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; 2311DRK48C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
