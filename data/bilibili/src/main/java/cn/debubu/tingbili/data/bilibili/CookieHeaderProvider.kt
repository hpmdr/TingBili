package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cookie 请求头供给（OkHttp 拦截器热路径专用）：
 * 原先每次请求都读 sessionFlow + 拼串（trim/join），虽然单次仅微秒级，
 * 但它是全工程最高频的调用点；这里按会话实例 + 60s 双条件缓存。
 * 登录/登出会换 session 实例，缓存即时失效，不会沿用旧身份。
 */
@Singleton
class CookieHeaderProvider @Inject constructor(
    private val authRepository: AuthRepository,
    private val buvidProvider: BuvidProvider,
) {
    @Volatile private var cachedSession: BiliSession? = null
    @Volatile private var cachedAtMs: Long = 0L
    @Volatile private var cachedHeader: String? = null

    fun get(): String? {
        val session = currentSession()
        val now = System.currentTimeMillis()
        if (session === cachedSession && now - cachedAtMs < REFRESH_MS) return cachedHeader
        val header = build(session, buvidProvider.cookie())
        cachedSession = session
        cachedAtMs = now
        cachedHeader = header
        return header
    }

    /**
     * 冷启动首帧会话还在后台加载（F7）：这里跑在 OkHttp 线程，有界等待即可，
     * 避免首批请求丢掉 Cookie 变成游客请求。已加载则零开销直接返回。
     */
    private fun currentSession(): BiliSession? {
        authRepository.sessionNow()?.let { return it }
        return runBlocking { withTimeoutOrNull(AWAIT_SESSION_MS) { authRepository.awaitSession() } }
    }

    internal fun build(session: BiliSession?, buvid: String?): String? =
        listOfNotNull(session?.toCookieHeader(), buvid)
            .map { it.trim().trim(';') }
            .filter { it.isNotBlank() }
            .joinToString("; ")
            .ifBlank { null }

    companion object {
        const val REFRESH_MS = 60_000L

        /** 等待启动期会话加载的上限：加密存储实测 ≈786ms，留一倍余量 */
        const val AWAIT_SESSION_MS = 1_500L
    }
}
