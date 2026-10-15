package cn.debubu.tingbili.di

import android.content.Context
import android.content.SharedPreferences
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

    override fun load(): BiliSession? = try {
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
        val e = prefs.edit()
        if (session == null) {
            e.clear()
        } else {
            e.putString(KEY_SESSDATA, session.sessdata)
            e.putString(KEY_JCT, session.biliJct)
            e.putLong(KEY_UID, session.uid)
            e.putString(KEY_UNAME, session.userName)
            e.putString(KEY_FACE, session.face)
            e.putString(KEY_REFRESH, session.refreshToken)
            e.putString(
                KEY_EXTRAS,
                Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), session.extras),
            )
        }
        e.apply()
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
