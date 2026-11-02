package cn.debubu.tingbili.core.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.model.episodeLabel
import cn.debubu.tingbili.core.data.model.queueLabel

/**
 * 直链管线（类 MediaSource 的数据源层）：cid 补齐 + MediaItem 组装 + 可恢复错误判定。
 *
 * 直链 **不在起播前批量解析**（一部 67 P 的视频会瞬间打 67 次 playurl，必被风控 -412，
 * 而且全部解析完才开始出声）。MediaItem 一律带惰性 URI，真正打开某个分 P 时才由
 * [AudioUrlResolver] 解析一次（见 ServicePlayerModule 的 ResolvingDataSource）。
 */
class PlayUrlPipeline(
    private val catalog: CatalogResolver,
    private val artwork: ArtworkResolver,
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
     * Track→MediaItem（mediaId `bvid:cid`：播放/刷新对位、测试断言都认它；URI 是惰性协议）。
     * 标题用队列序号（`queueIndex` 为 0-based 下标）：混播时 `P{n}` 各说各话，
     * 只有队列序号是统一口径；不传则退化到分 P 标题（兼容旧调用）。
     */
    fun buildItem(track: Track, imageFormat: ImageFormat, queueIndex: Int? = null): MediaItem =
        MediaItem.Builder()
            .setUri(AudioUrlResolver.lazyUri(track.bvid, track.cid))
            .setMediaId("${track.bvid}:${track.cid}")
            .setCustomCacheKey("${track.bvid}:${track.cid}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(
                        if (queueIndex != null && queueIndex >= 0) track.queueLabel(queueIndex)
                        else track.episodeLabel()
                    )
                    .setArtist(track.author)
                    .setArtworkUri(artwork.artwork(track.cover, imageFormat))
                    .build()
            )
            .build()

    /** 可恢复的直链错误（过期/403/断网/直链解析失败）：值得重试；其余直接报 Result.Error。 */
    fun isRecoverable(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            || error.errorCodeName.contains("403", ignoreCase = true)
            || error.message?.contains("403") == true
            || error.message?.contains("expire", ignoreCase = true) == true
            || error.message?.contains(AudioUrlResolver.RESOLVE_FAILED_MARK, ignoreCase = true) == true

    companion object {
        /** 惰性直链协议的 scheme；ServicePlayerModule 据此识别需要现场解析的 MediaItem */
        const val LAZY_SCHEME = "tingbili-audio"
        const val PARAM_BVID = "bvid"
        const val PARAM_CID = "cid"

        fun isLazyUri(uri: Uri): Boolean = uri.scheme == LAZY_SCHEME

        fun bvidOf(uri: Uri): String? = uri.getQueryParameter(PARAM_BVID)
        fun cidOf(uri: Uri): Long? = uri.getQueryParameter(PARAM_CID)?.toLongOrNull()
    }
}
