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
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

    /** 主链挂掉后暂存的备链（@Singleton 常驻内存；键带音质偏好，进程重启即失效回网络）。 */
    private val backupPool = CdnBackupPool()

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
        val backupKey = "$bvid:$cid:$prefer"
        // 主链上次挂掉暂存的备链：播放失败重试会先删磁盘缓存再重解析，
        // 这里直接给下一条备链，不用再打一次 playurl（原地换线路）。
        // 键带偏好档：换音质会删缓存重解析，新档必须走网络拿新直链，不能吃旧档备链。
        backupPool.poll(backupKey)?.let {
            android.util.Log.i("TingBiliPlay", "backup $backupKey -> ${it.url}")
            return it
        }
        val coolingDown = System.currentTimeMillis() < guestUntilMs
        var r = if (coolingDown) {
            repo.getPlayUrls(bvid, cid, prefer, guest = true)
        } else {
            repo.getPlayUrls(bvid, cid, prefer)
        }
        if (r is Result.Error) {
            android.util.Log.w("TingBiliPlay", "resolve $bvid:$cid qn=$prefer failed: ${r.msg}")
            // 断网直接抛（带 cause）：深夜省电断网时跳过游客重试那次必死的请求，快速失败；
            // 调用方（AudioUrlResolver）认 isConnectivityError 做过期缓存兜底 + 透传分类。
            // 风控/业务错继续走 null（游客退路不受影响）。
            if (isConnectivityError(r.throwable)) throw IOException("$CONNECTIVITY_MARK，请稍后重试", r.throwable)
            if (!coolingDown && isRiskControlled(r.msg)) {
                guestUntilMs = System.currentTimeMillis() + RISK_COOLDOWN_MS
                r = repo.getPlayUrls(bvid, cid, prefer, guest = true)
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
            is Result.Success -> {
                val c = r.data
                // 实际选中档（标称码率 + 音质 code）带回给调用方：码率兜底 + 底部标签显示实际生效档
                backupPool.stage(
                    backupKey,
                    c.urls.drop(1).map { ResolvedAudio(it, c.bandwidthBps, c.audioCode) },
                )
                ResolvedAudio(c.urls.first(), c.bandwidthBps, c.audioCode, c.urls.drop(1))
            }
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

/**
 * CDN 备链暂存：主链建连超时/403 后的自动兜底（PiliPlus 选线路的数据源，自动版）。
 *
 * 生产链路：首次解析把备链 stage 进来；播放失败时 PlayerManager 删磁盘缓存行并重建
 * 惰性 item，DataSource 重开时再次 resolve，这里 poll 出下一条备链直接用，不用重新
 * 打 playurl（省一次鉴权往返，也避开"新 playurl 又分到同一个坏节点"）。
 * 进程重启/备链取完/过期（与直链磁盘缓存同 TTL）后自动回落到网络重解析。
 * 只在内存里，不进 Room：键带音质偏好，换档不会吃到旧档备链。
 */
class CdnBackupPool(internal val nowMs: () -> Long = System::currentTimeMillis) {
    private data class Entry(val queued: ArrayDeque<ResolvedAudio>, val stagedAtMs: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    /** 记下本次解析的备链；同 key 直接覆盖（重解析/换档以上次为准）。 */
    fun stage(key: String, backups: List<ResolvedAudio>) {
        if (backups.isEmpty()) return
        if (map.size >= MAX_KEYS) map.clear()
        map[key] = Entry(ArrayDeque(backups), nowMs())
    }

    /** 取下一条备链；取完或过期自动清理并返回 null（调用方走网络重解析）。 */
    fun poll(key: String): ResolvedAudio? {
        val e = map[key] ?: return null
        if (nowMs() - e.stagedAtMs >= TTL_MS) {
            map.remove(key)
            return null
        }
        val next = e.queued.removeFirstOrNull()
        if (e.queued.isEmpty()) map.remove(key)
        return next
    }

    companion object {
        /** 与 PlayUrlCacheEntity.TTL_MS 对齐：备链和主链同一批过期 */
        const val TTL_MS = 90 * 60 * 1000L
        const val MAX_KEYS = 500
    }
}
