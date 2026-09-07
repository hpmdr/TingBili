package cn.debubu.tingbili.data.bilibili.dto

import cn.debubu.tingbili.core.data.model.Track
import kotlinx.serialization.Serializable

@Serializable
data class ViewDto(
    val code: Int = 0,
    val message: String = "",
    val data: ViewData? = null
)

@Serializable
data class ViewData(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val desc: String = "",
    val owner: Owner? = null,
    val pages: List<ViewPage> = emptyList(),
    val duration: Int = 0,
    /** 分区名（如"音乐"、"知识"） */
    val tname: String = "",
    /** 发布时间（epoch 秒） */
    val pubdate: Long = 0,
    /** 分 P 总数 */
    val videos: Int = 1,
    val stat: ViewStat? = null,
    val subtitle: ViewSubtitleInfo? = null,
    // fallback for whole video duration if pages empty
    val cid: Long = 0
)

@Serializable
data class Owner(
    val name: String = "",
    val mid: Long = 0,
    /** UP主头像 URL */
    val face: String = ""
)

/** 视频数据统计（来自 view 接口 stat 字段） */
@Serializable
data class ViewStat(
    val view: Long = 0,
    val danmaku: Long = 0,
    val reply: Long = 0,
    val favorite: Long = 0,
    val coin: Long = 0,
    val share: Long = 0,
    val like: Long = 0
)

@Serializable
data class ViewPage(
    val cid: Long = 0L,
    val page: Int = 0,
    val part: String = "",
    val duration: Int = 0,
    val dimension: Dimension? = null
)

@Serializable
data class Dimension(
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class ViewSubtitleInfo(
    val list: List<ViewSubtitleItem> = emptyList()
)

@Serializable
data class ViewSubtitleItem(
    val id: Long = 0L,
    val lan: String = "",
    val subtitle_url: String = ""
)

fun ViewDto.toTracks(): List<Track> = data?.toTracks() ?: emptyList()

fun ViewData.toTracks(): List<Track> {
    if (pages.isEmpty()) {
        // Single-part fallback
        val cidFallback = cid.takeIf { it != 0L } ?: 0L
        return listOf(
            Track(
                bvid = bvid,
                cid = cidFallback,
                title = title,
                author = owner?.name ?: "",
                cover = pic.normalizeCover(),
                durationMs = duration * 1000L,
                subtitleUrl = subtitle?.list?.firstOrNull()?.subtitle_url?.takeIf { it.isNotBlank() },
                pageCount = 1
            )
        )
    }
    val cover = pic.normalizeCover()
    val author = owner?.name ?: ""
    // Prefer page.part title if multi-part, else use main title
    val mainTitle = title
    return pages.map { page ->
        val title = if (pages.size == 1) mainTitle else page.part.ifBlank { "$mainTitle P${page.page}" }
        Track(
            bvid = bvid,
            cid = page.cid,
            title = title,
            author = author,
            cover = cover,
            durationMs = page.duration * 1000L,
            subtitleUrl = subtitle?.list?.firstOrNull()?.subtitle_url?.takeIf { it.isNotBlank() },
            pageCount = pages.size
        )
    }
}
