package cn.debubu.tingbili.di

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SESSDATA 等登录凭证的加密落盘（等同长期密码，禁止明文）。
 * 读失败（换签名/损坏）视为未登录，不抛异常。
 */
@Singleton
class EncryptedAuthStore @Inject constructor(
    @ApplicationContext ctx: Context,
) : AuthStore {
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            ctx,
            FILE_NAME,
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // 内存镜像（F7）：构造期同步读盘会阻塞主线程，实测首次建 MasterKey ≈786ms。
    // 改成「随便谁先读一次 → 之后全走内存」，并暴露 prime() 供 Application 在 IO 线程预热。
    @Volatile private var cached: BiliSession? = null
    @Volatile private var loaded = false

    /** IO 线程预热：把建 MasterKey + 解密读盘从主线程挪走（F7，见 docs/FIX_PLAN.md §7.1） */
    fun prime() {
        if (loaded) return
        cached = readSession()
        loaded = true
    }

    override fun load(): BiliSession? {
        if (loaded) return cached
        return readSession().also { cached = it; loaded = true }
    }

    private fun readSession(): BiliSession? = try {
        val sessdata = prefs.getString(KEY_SESSDATA, null).orEmpty()
        val uid = prefs.getLong(KEY_UID, 0L)
        if (sessdata.isBlank() || uid <= 0L) return null
        BiliSession(
            sessdata = sessdata,
            biliJct = prefs.getString(KEY_JCT, "").orEmpty(),
            uid = uid,
            userName = prefs.getString(KEY_UNAME, "").orEmpty(),
            face = prefs.getString(KEY_FACE, "").orEmpty(),
            refreshToken = prefs.getString(KEY_REFRESH, "").orEmpty(),
            extras = prefs.getString(KEY_EXTRAS, null)?.let {
                Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)
            } ?: emptyMap(),
        )
    } catch (_: Exception) {
        null
    }

    override fun save(session: BiliSession?) {
        cached = session
        loaded = true
        prefs.edit {
            if (session == null) {
                clear()
            } else {
                putString(KEY_SESSDATA, session.sessdata)
                putString(KEY_JCT, session.biliJct)
                putLong(KEY_UID, session.uid)
                putString(KEY_UNAME, session.userName)
                putString(KEY_FACE, session.face)
                putString(KEY_REFRESH, session.refreshToken)
                putString(
                    KEY_EXTRAS,
                    Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), session.extras),
                )
            }
        }
    }

    companion object {
        private const val FILE_NAME = "tingbili_auth"
        private const val KEY_SESSDATA = "sessdata"
        private const val KEY_JCT = "bili_jct"
        private const val KEY_UID = "uid"
        private const val KEY_UNAME = "uname"
        private const val KEY_FACE = "face"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXTRAS = "extras"
    }
}
