package cn.debubu.tingbili.data.bilibili

import android.net.Uri
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.ArtworkResolver
import cn.debubu.tingbili.core.media.CatalogResolver
import cn.debubu.tingbili.core.media.PlayUrlResolver
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * core:media 三个数据源接口的 B 站实现。
 * 风控语义收敛在这里：412/空直链一律转 null/空列表，播放器只管跳过。
 */
class BiliMediaResolvers @Inject constructor(
    private val repo: BiliRepository,
    private val prefs: PreferencesRepository,
) : PlayUrlResolver, CatalogResolver, ArtworkResolver {

    /** 登录态最近一次被风控的时间点；冷却期内直接走游客，避免每首都先撞一次 412。 */
    @Volatile
    private var guestUntilMs = 0L

    /**
     * 解析直链按用户音质偏好选档（播放页底部音质入口/默认 132K），失败回落调用方跳过，不断播。
     * 登录态被风控（412「神秘力量」）时退游客身份：音质可能降级，但能继续放；
     * 随后 [RISK_COOLDOWN_MS] 内直接走游客，不再每条都先撞墙。
     */
    override suspend fun resolve(bvid: String, cid: Long): String? {
        val prefer = try {
            prefs.audioQuality.first()
        } catch (_: Exception) {
            AudioQuality.DEFAULT_CODE
        }
        val coolingDown = System.currentTimeMillis() < guestUntilMs
        var r = if (coolingDown) {
            repo.getPlayUrl(bvid, cid, prefer, guest = true)
        } else {
            repo.getPlayUrl(bvid, cid, prefer)
        }
        if (r is Result.Error) {
            android.util.Log.w("TingBiliPlay", "resolve $bvid:$cid qn=$prefer failed: ${r.msg}")
            if (!coolingDown && isRiskControlled(r.msg)) {
                guestUntilMs = System.currentTimeMillis() + RISK_COOLDOWN_MS
                r = repo.getPlayUrl(bvid, cid, prefer, guest = true)
                android.util.Log.i(
                    "TingBiliPlay",
                    "guest retry $bvid:$cid -> ${if (r is Result.Success) "ok" else (r as Result.Error).msg}",
                )
            }
        }
        return when (r) {
            is Result.Success -> r.data.takeIf { it.isNotBlank() }
            is Result.Error -> null
        }
    }

    /** B 站风控文案：`受到神秘力量干扰` / code -412 */
    private fun isRiskControlled(msg: String): Boolean =
        msg.contains("神秘力量") || msg.contains("412")

    private companion object {
        /** 风控冷却：期间直接用游客身份，结束后再探测登录态是否恢复 */
        const val RISK_COOLDOWN_MS = 10 * 60 * 1000L
    }

    override suspend fun tracksOf(bvid: String): List<Track> =
        when (val v = repo.getView(bvid)) {
            is Result.Success -> v.data
            is Result.Error -> emptyList()
        }

    override fun artwork(cover: String, format: ImageFormat): Uri? =
        cover.takeIf { it.isNotBlank() }
            ?.biliImage(BiliImageVariant.ListThumb, format)
            ?.let(Uri::parse)
}
