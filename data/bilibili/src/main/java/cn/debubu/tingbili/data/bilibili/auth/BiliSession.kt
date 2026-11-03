package cn.debubu.tingbili.data.bilibili.auth

/**
 * B 站网页登录态：扫码 / Cookie 粘贴成功后落盘的凭证。
 * SESSDATA 为身份核心（等同长期密码），必须加密存储，禁止打日志。
 */
data class BiliSession(
    val sessdata: String,
    val biliJct: String,
    val uid: Long,
    val userName: String = "",
    val face: String = "",
    /**
     * 扫码成功时 B 站下发的 refresh_token，会话续期的唯一凭证。
     * 老会话没有它（需重扫一次），[AuthRepository.maintainSession] 会跳过。
     */
    val refreshToken: String = "",
    /** 换链路带回的其他 Cookie（如 DedeUserID__ckMd5、sid），原样回传 */
    val extras: Map<String, String> = emptyMap(),
) {
    /**
     * 拼成请求 Cookie 头；调用方再与 buvid3/4 拼接。
     * uid 未知（用户只粘了 SESSDATA）时不带 DedeUserID，避免把 0 发给服务端。
     */
    fun toCookieHeader(): String = buildString {
        append("SESSDATA=").append(sessdata)
        if (biliJct.isNotBlank()) append("; bili_jct=").append(biliJct)
        if (uid > 0L) append("; DedeUserID=").append(uid)
        // buvid3/4 单独处理：CookieHeaderProvider 优先用会话自带的（浏览器那份），
        // 这里不再重复拼，避免出现两对 buvid。
        extras.forEach { (k, v) ->
            if (k != KEY_BUVID3 && k != KEY_BUVID4) append("; ").append(k).append('=').append(v)
        }
    }

    companion object {
        private val COOKIE_PAIR = Regex("\\s*([^=;\\s]+)=([^;]*)")

        /** 换行在 Cookie 里非法，用户按行复制时等价于分隔符 */
        private val NEWLINE = Regex("[\\r\\n]+")

        private const val KEY_SESSDATA = "SESSDATA"
        private const val KEY_UID = "DedeUserID"

        /** Cookie 导入时连同浏览器那份 buvid 一起带回：换成新 buvid 会被风控判 -351（详见 CookieHeaderProvider）。 */
        internal const val KEY_BUVID3 = "buvid3"
        internal const val KEY_BUVID4 = "buvid4"

        /** 允许原样回传的附加字段：会话相关（sid/ckMd5）与账号绑定的 buvid。 */
        private val EXTRA_WHITELIST = setOf("DedeUserID__ckMd5", "sid", KEY_BUVID3, KEY_BUVID4)

        /** 唯一必填字段；uid 可以由 nav 接口补 */
        private val REQUIRED_FIELDS = listOf(KEY_SESSDATA)

        /**
         * 解析用户粘贴的 Cookie（整段 document.cookie / DevTools 多行复制 / 三件套均可）。
         * SESSDATA 缺一不可，否则返回 null；DedeUserID 可缺，uid 由 nav 兜底。
         */
        fun parseCookieHeader(raw: String): BiliSession? {
            val pairs = parsePairs(raw)
            val sessdata = pairs[KEY_SESSDATA].orEmpty()
            if (sessdata.isBlank()) return null
            // 白名单回传：fingerprint 等无关字段丢弃；buvid 必须保留（账号在浏览器里绑的就是它，
            // 换成 App 新生成的 buvid 打 playurl 会稳定吃 -351，2026-11 实测）
            val extras = pairs.filterKeys { it in EXTRA_WHITELIST }
            return BiliSession(
                sessdata = sessdata,
                biliJct = pairs["bili_jct"].orEmpty(),
                uid = (pairs[KEY_UID]?.toLongOrNull() ?: 0L).coerceAtLeast(0L),
                extras = extras,
            )
        }

        /**
         * 解析失败时给用户定位信息：缺哪个必填字段（空列表表示格式完全认不出）。
         * 与 [parseCookieHeader] 共用同一套拆解规则，避免提示和实际判断打架。
         */
        fun missingFields(raw: String): List<String> {
            val pairs = parsePairs(raw)
            return REQUIRED_FIELDS.filter { pairs[it].isNullOrBlank() }
        }

        private fun parsePairs(raw: String): Map<String, String> =
            // 用户常从 DevTools 的 Cookies 面板按行复制；值本身不含换行，
            // 先压成 ';' 再匹配，否则 SESSDATA 的值会把下一行整段吞掉
            COOKIE_PAIR.findAll(raw.replace(NEWLINE, ";"))
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }
}
