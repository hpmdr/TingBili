package cn.debubu.tingbili.data.bilibili.dto

import cn.debubu.tingbili.core.data.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchDto(
    val code: Int = 0,
    val message: String = "",
    val data: SearchData? = null
)

@Serializable
data class SearchData(
    val result: List<SearchItem> = emptyList(),
    val page: Int = 1,
    @SerialName("numResults") val numResults: Int = 0,
    @SerialName("numPages") val numPages: Int = 0
)

@Serializable
data class SearchItem(
    val bvid: String = "",
    val title: String = "",
    val author: String = "",
    val pic: String = "",
    val duration: String = "0:00",
    @SerialName("type") val type: String = "video",
    // 实测搜索接口不返回该字段（null）；保留映射以便将来 B 站补上时直接生效
    val videos: Int? = null,
    // UP 主 mid，点作者进主页用
    val mid: Long = 0,
    // some fields from Bili may be present, ignore unknown
    val aid: Long = 0
)

fun SearchDto.toTracks(): List<Track> {
    val items = data?.result ?: emptyList()
    return items.filter { it.bvid.isNotBlank() }.map { item ->
        Track(
            bvid = item.bvid,
            cid = 0L,
            title = item.title.stripHtml(),
            author = item.author,
            cover = item.pic.normalizeBiliImageUrl(),
            durationMs = item.duration.parseDurationToMs(),
            subtitleUrl = null,
            pageCount = (item.videos ?: 0).coerceAtLeast(1),
            upMid = item.mid.takeIf { it > 0 }
        )
    }
}

internal fun String.stripHtml(): String {
    return this.replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}

internal fun String.parseDurationToMs(): Long {
    if (isBlank()) return 0L
    try {
        val parts = trim().split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0L
        return when (parts.size) {
            3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L)
            2 -> ((parts[0] * 60 + parts[1]) * 1000L)
            1 -> (parts[0] * 1000L)
            else -> 0L
        }
    } catch (_: Exception) {
        return 0L
    }
}
