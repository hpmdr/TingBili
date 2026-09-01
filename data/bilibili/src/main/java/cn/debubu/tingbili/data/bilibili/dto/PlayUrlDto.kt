package cn.debubu.tingbili.data.bilibili.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayUrlDto(
    val code: Int = 0,
    val message: String = "",
    val data: PlayUrlData? = null
)

@Serializable
data class PlayUrlData(
    val durl: List<DurlItem>? = null,
    val dash: DashData? = null
)

@Serializable
data class DurlItem(
    val url: String = "",
    val length: Long = 0L,
    val size: Long = 0L
)

@Serializable
data class DashData(
    val audio: List<DashAudio>? = null,
    val video: List<DashVideo>? = null
)

@Serializable
data class DashAudio(
    @SerialName("baseUrl") val baseUrl: String = "",
    @SerialName("base_url") val baseUrlAlt: String = "",
    val id: Int = 0,
    val bandwidth: Int = 0
) {
    fun resolvedUrl(): String = if (baseUrl.isNotBlank()) baseUrl else baseUrlAlt
}

@Serializable
data class DashVideo(
    @SerialName("baseUrl") val baseUrl: String = "",
    @SerialName("base_url") val baseUrlAlt: String = ""
)

fun PlayUrlDto.toAudioUrl(): String? {
    val d = data ?: return null
    // Prefer dash audio highest bandwidth -> first typically highest
    val dashUrl = d.dash?.audio?.firstOrNull()?.resolvedUrl()?.takeIf { it.isNotBlank() }
    if (!dashUrl.isNullOrBlank()) return dashUrl
    val durlUrl = d.durl?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
    return durlUrl
}
