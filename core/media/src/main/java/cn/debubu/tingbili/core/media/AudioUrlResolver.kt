package cn.debubu.tingbili.core.media

import android.net.Uri
import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.network.CONNECTIVITY_MARK
import cn.debubu.tingbili.core.data.network.isConnectivityError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * 惰性直链解析：播放器打开某个分 P 时才解析一次 playurl（带磁盘缓存）。
 *
 * 之前是起播前把整个队列的直链一次性解析完，67 P 的视频会瞬间打 67 次接口，
 * 直接被 B 站风控（`受到神秘力量干扰` = 412），而且解析完之前一声不出。
 *
 * 调用点是 ExoPlayer 的加载线程，只能同步返回，所以内部用 [runBlocking]；
 * 实际只在该 item 首次打开/缓存失效时发生一次网络请求。
 */
@Singleton
class AudioUrlResolver @Inject constructor(
    private val playUrls: PlayUrlResolver,
    private val cache: PlayUrlCacheDao,
) {
    /**
     * 解析可播放直链：
     * 1. 命中新鲜缓存 → 直接用（重播/续播不重复请求）；
     * 2. 否则现场解析一次并回写缓存；
     * 3. 解析失败（风控/断网）且有过期缓存 → 用旧直链兜底（配合 CacheDataSource，已缓存音频仍能放）。
     * @throws IOException 无任何可用直链，交给播放器错误重试链路
     */
    fun resolveBlocking(bvid: String, cid: Long): String {
        val key = "$bvid:$cid"
        val now = System.currentTimeMillis()
        val cached = runCatching { runBlocking { cache.get(key) } }.getOrNull()
        // 带宽为 0 的旧行（升级前写入）视为未登记，重解析一次把码率补上；
        // 极少数 durl-only 老视频每播一次多打一次 playurl，可接受。
        if (cached != null && cached.isFresh(now) && cached.bandwidthBps > 0) {
            // 缓存命中也要登记：码率兜底 + 底部标签显示「实际生效档」都靠它
            AudioBitrateRegistry.put(bvid, cid, cached.bandwidthBps, cached.audioCode)
            return cached.url
        }

        // 断网异常不吞成 null：先看有没有过期缓存能顶（不断播），没有才透传，
        // 调用方的错误链路认 isConnectivityError(cause) 做分类 + 网络恢复重试门控。
        val fresh: ResolvedAudio? = try {
            runBlocking { playUrls.resolve(bvid, cid) }
        } catch (e: Exception) {
            if (!isConnectivityError(e)) null
            else cached?.url?.takeIf { it.isNotBlank() }?.let {
                AudioBitrateRegistry.put(bvid, cid, cached.bandwidthBps, cached.audioCode)
                return it
            } ?: throw IOException("$CONNECTIVITY_MARK，请检查网络后重试", e)
        }
        if (fresh != null && fresh.url.isNotBlank()) {
            AudioBitrateRegistry.put(bvid, cid, fresh.bandwidthBps, fresh.audioCode)
            runCatching {
                runBlocking {
                    cache.putAll(
                        listOf(
                            PlayUrlCacheEntity(key, fresh.url, now, fresh.bandwidthBps, fresh.audioCode)
                        )
                    )
                    cache.pruneExpired(now - PlayUrlCacheEntity.TTL_MS)
                }
            }
            return fresh.url
        }

        cached?.url?.takeIf { it.isNotBlank() }?.let {
            AudioBitrateRegistry.put(bvid, cid, cached.bandwidthBps, cached.audioCode)
            return it
        }
        throw IOException("$RESOLVE_FAILED_MARK（可能触发风控），请稍后再试")
    }

    companion object {
        /** 直链解析失败的标记：播放错误链路据此判定为可重试 */
        const val RESOLVE_FAILED_MARK = "音源解析失败"

        fun lazyUri(bvid: String, cid: Long): Uri = Uri.Builder()
            .scheme(PlayUrlPipeline.LAZY_SCHEME)
            .authority("play")
            .appendQueryParameter(PlayUrlPipeline.PARAM_BVID, bvid)
            .appendQueryParameter(PlayUrlPipeline.PARAM_CID, cid.toString())
            .build()
    }
}
