package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 直链管线（类 MediaSource 的数据源层）：cid 补齐 + playurl 批量解析 + MediaItem 组装 +
 * 过期刷新 + 可恢复错误判定。不碰 transport/_state，只做数据。
 */
class PlayUrlPipeline(
    private val playUrls: PlayUrlResolver,
    private val catalog: CatalogResolver,
    private val artwork: ArtworkResolver,
    private val playUrlCache: PlayUrlCacheDao,
) {
    /** cid=0（搜索结果）时经目录解析第一个分 P 的真实 cid */
    suspend fun resolveCid(track: Track): Track =
        catalog.tracksOf(track.bvid).firstOrNull()?.let { first ->
            track.copy(
                cid = first.cid,
                durationMs = first.durationMs.takeIf { it > 0L } ?: track.durationMs,
            )
        } ?: track

    /**
     * playurl 批量解析：持久缓存命中即用，未命中并发补（限 4 并发防风控），结果回写。
     * 返回 track→url（解析失败的项直接丢掉，调用方跳过）。
     * 冷启动/重装恢复时缓存命中则 0 网络解析、秒开。
     */
    suspend fun resolveUrls(tracks: List<Track>): Map<Track, String> {
        if (tracks.isEmpty()) return emptyMap()
        val nowMs = System.currentTimeMillis()
        val urlCache = try {
            playUrlCache.getAll().associateBy { it.key }
        } catch (_: Exception) {
            emptyMap()
        }
        val out = mutableMapOf<Track, String>()
        val missing = mutableListOf<Track>()
        for (t in tracks) {
            val hit = urlCache["${t.bvid}:${t.cid}"]?.takeIf { it.isFresh(nowMs) }?.url
            if (hit.isNullOrBlank()) missing.add(t) else out[t] = hit
        }
        if (missing.isNotEmpty()) {
            val semaphore = Semaphore(4)
            val fetched = supervisorScope {
                missing.map { track ->
                    async {
                        runCatching {
                            semaphore.withPermit {
                                val url = playUrls.resolve(track.bvid, track.cid)?.takeIf { it.isNotBlank() }
                                if (url != null) track to url else null
                            }
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            if (fetched.isNotEmpty()) {
                playUrlCache.putAll(
                    fetched.map { (t, u) -> PlayUrlCacheEntity("${t.bvid}:${t.cid}", u, nowMs) }
                )
                playUrlCache.pruneExpired(nowMs - PlayUrlCacheEntity.TTL_MS)
                playUrlCache.pruneOverflow(PlayUrlCacheEntity.MAX_ENTRIES)
                fetched.forEach { (t, u) -> out[t] = u }
            }
        }
        return out
    }

    /** Track→MediaItem（mediaId `bvid:cid`：播放/刷新对位、测试断言都认它） */
    fun buildItem(track: Track, url: String, imageFormat: ImageFormat): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId("${track.bvid}:${track.cid}")
            .setCustomCacheKey("${track.bvid}:${track.cid}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.author)
                    .setArtworkUri(artwork.artwork(track.cover, imageFormat))
                    .build()
            )
            .build()

    /**
     * 单条直链刷新（403 过期/主动刷新共用）：解析 + 回写缓存。
     * 解析失败（null/空）返回 null，调用方沿用旧 URI，下次错误再重试。
     */
    suspend fun refreshUrl(track: Track): String? {
        val newUrl = try {
            playUrls.resolve(track.bvid, track.cid)
        } catch (_: Exception) {
            null
        }
        if (newUrl.isNullOrBlank()) return null
        try {
            playUrlCache.putAll(listOf(PlayUrlCacheEntity("${track.bvid}:${track.cid}", newUrl, System.currentTimeMillis())))
        } catch (_: Exception) {
        }
        return newUrl
    }

    /** 可恢复的直链错误（过期/403/断网）：值得花一次重试；其余直接报 Result.Error。 */
    fun isRecoverable(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            || error.errorCodeName.contains("403", ignoreCase = true)
            || error.message?.contains("403") == true
            || error.message?.contains("expire", ignoreCase = true) == true
}
