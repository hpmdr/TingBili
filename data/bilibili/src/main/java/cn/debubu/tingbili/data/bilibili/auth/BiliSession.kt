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
    /** 换链路带回的其他 Cookie（如 DedeUserID__ckMd5、sid），原样回传 */
    val extras: Map<String, String> = emptyMap(),
) {
    /** 拼成请求 Cookie 头；调用方再与 buvid3/4 拼接 */
    fun toCookieHeader(): String = buildString {
        append("SESSDATA=").append(sessdata)
        if (biliJct.isNotBlank()) append("; bili_jct=").append(biliJct)
        append("; DedeUserID=").append(uid)
        extras.forEach { (k, v) -> append("; ").append(k).append('=').append(v) }
    }

    companion object {
        private val COOKIE_PAIR = Regex("\\s*([^=;\\s]+)=([^;]*)")

        /**
         * 解析用户粘贴的 Cookie（整段 document.cookie 或 SESSDATA… 三件套均可）。
         * SESSDATA + DedeUserID 缺一不可，否则返回 null。
         */
        fun parseCookieHeader(raw: String): BiliSession? {
            val pairs = COOKIE_PAIR.findAll(raw).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val sessdata = pairs["SESSDATA"].orEmpty()
            val uid = pairs["DedeUserID"]?.toLongOrNull() ?: 0L
            if (sessdata.isBlank() || uid <= 0L) return null
            // 白名单回传：陈旧的 buvid/fingerprint 会干扰风控，一律丢弃
            val extras = pairs.filterKeys { it == "DedeUserID__ckMd5" || it == "sid" }
            return BiliSession(
                sessdata = sessdata,
                biliJct = pairs["bili_jct"].orEmpty(),
                uid = uid,
                extras = extras,
            )
        }
    }
}
