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
    val owner: Owner? = null,
    val pages: List<ViewPage> = emptyList(),
    val duration: Int = 0,
    val subtitle: ViewSubtitleInfo? = null,
    // fallback for whole video duration if pages empty
    val cid: Long = 0
)

@Serializable
data class Owner(
    val name: String = "",
    val mid: Long = 0
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

fun ViewDto.toTracks(): List<Track> {
    val vd = data ?: return emptyList()
    if (vd.pages.isEmpty()) {
        // Single-part fallback
        val cidFallback = vd.cid.takeIf { it != 0L } ?: 0L
        return listOf(
            Track(
                bvid = vd.bvid,
                cid = cidFallback,
                title = vd.title,
                author = vd.owner?.name ?: "",
                cover = vd.pic.normalizeCover(),
                durationMs = vd.duration * 1000L,
                subtitleUrl = vd.subtitle?.list?.firstOrNull()?.subtitle_url?.takeIf { it.isNotBlank() }
            )
        )
    }
    val cover = vd.pic.normalizeCover()
    val author = vd.owner?.name ?: ""
    // Prefer page.part title if multi-part, else use main title
    val mainTitle = vd.title
    return vd.pages.map { page ->
        val title = if (vd.pages.size == 1) mainTitle else page.part.ifBlank { "$mainTitle P${page.page}" }
        Track(
            bvid = vd.bvid,
            cid = page.cid,
            title = title,
            author = author,
            cover = cover,
            durationMs = page.duration * 1000L,
            subtitleUrl = vd.subtitle?.list?.firstOrNull()?.subtitle_url?.takeIf { it.isNotBlank() }
        )
    }
}
