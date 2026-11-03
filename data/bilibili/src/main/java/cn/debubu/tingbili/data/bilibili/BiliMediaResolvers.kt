package cn.debubu.tingbili.data.bilibili

import android.net.Uri
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.network.CONNECTIVITY_MARK
import cn.debubu.tingbili.core.data.network.isConnectivityError
import cn.debubu.tingbili.core.media.ArtworkResolver
import cn.debubu.tingbili.core.media.CatalogResolver
import cn.debubu.tingbili.core.media.PlayUrlResolver
import cn.debubu.tingbili.core.media.ResolvedAudio
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality
import cn.debubu.tingbili.data.bilibili.dto.DashAudio
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import java.io.IOException
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
    override suspend fun resolve(bvid: String, cid: Long): ResolvedAudio? {
        val prefer = try {
            prefs.audioQuality.first()
        } catch (_: Exception) {
            AudioQuality.DEFAULT_CODE
        }
        val coolingDown = System.currentTimeMillis() < guestUntilMs
        // 实际选中档（标称码率 + 音质 code）带回给调用方：码率兜底 + 底部标签显示实际生效档
        var bandwidthBps = 0
        var audioCode = 0
        val rememberBitrate: (DashAudio) -> Unit = {
            bandwidthBps = it.bandwidth
            audioCode = it.id
        }
        var r = if (coolingDown) {
            repo.getPlayUrl(bvid, cid, prefer, guest = true, onAudioSelected = rememberBitrate)
        } else {
            repo.getPlayUrl(bvid, cid, prefer, onAudioSelected = rememberBitrate)
        }
        if (r is Result.Error) {
            android.util.Log.w("TingBiliPlay", "resolve $bvid:$cid qn=$prefer failed: ${r.msg}")
            // 断网直接抛（带 cause）：深夜省电断网时跳过游客重试那次必死的请求，快速失败；
            // 调用方（AudioUrlResolver）认 isConnectivityError 做过期缓存兜底 + 透传分类。
            // 风控/业务错继续走 null（游客退路不受影响）。
            if (isConnectivityError(r.throwable)) throw IOException("$CONNECTIVITY_MARK，请稍后重试", r.throwable)
            if (!coolingDown && isRiskControlled(r.msg)) {
                guestUntilMs = System.currentTimeMillis() + RISK_COOLDOWN_MS
                bandwidthBps = 0
                audioCode = 0
                r = repo.getPlayUrl(bvid, cid, prefer, guest = true, onAudioSelected = rememberBitrate)
                android.util.Log.i(
                    "TingBiliPlay",
                    "guest retry $bvid:$cid -> ${if (r is Result.Success) "ok" else (r as Result.Error).msg}",
                )
            }
        }
        if (r is Result.Error && isConnectivityError(r.throwable)) {
            throw IOException("$CONNECTIVITY_MARK，请稍后重试", r.throwable)
        }
        return when (r) {
            is Result.Success -> r.data.takeIf { it.isNotBlank() }
                ?.let { ResolvedAudio(it, bandwidthBps, audioCode) }
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
