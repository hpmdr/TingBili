package cn.debubu.tingbili.data.bilibili.dto

import cn.debubu.tingbili.data.bilibili.model.LyricLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubtitleDto(
    val code: Int = 0,
    val message: String = "",
    val data: SubtitleData? = null
)

@Serializable
data class SubtitleData(
    val subtitle: SubtitleInfo? = null,
    // Direct body case: subtitle file already fetched (mock)
    val body: List<SubtitleBodyItem>? = null,
    @SerialName("subtitles") val subtitles: List<SubtitleItem>? = null
)

@Serializable
data class SubtitleInfo(
    val subtitles: List<SubtitleItem> = emptyList(),
    // Some APIs embed body directly in subtitle info
    val body: List<SubtitleBodyItem>? = null
)

@Serializable
data class SubtitleItem(
    val id: Long = 0L,
    val lan: String = "",
    @SerialName("lan_doc") val lanDoc: String = "",
    @SerialName("subtitle_url") val subtitleUrl: String = ""
)

@Serializable
data class SubtitleBodyItem(
    val from: Double = 0.0,
    val to: Double = 0.0,
    val content: String = "",
    val sid: Long = 0L,
    val location: Int = 2,
    val music: Double = 0.0
)

fun SubtitleDto.toLyricLines(): List<LyricLine> {
    // Prefer direct body in data.body
    val directBody = data?.body
    if (!directBody.isNullOrEmpty()) {
        return directBody.map { LyricLine((it.from * 1000).toLong(), it.content) }
    }
    // Body inside subtitle info
    val infoBody = data?.subtitle?.body
    if (!infoBody.isNullOrEmpty()) {
        return infoBody.map { LyricLine((it.from * 1000).toLong(), it.content) }
    }
    // If subtitles list present but no body, this requires secondary fetch of subtitle_url
    // For visitor gateway we return empty here; future implementation will fetch subtitle_url content
    // via OkHttp and parse. Keep stub returning empty to avoid extra network in unit test.
    return emptyList()
}
