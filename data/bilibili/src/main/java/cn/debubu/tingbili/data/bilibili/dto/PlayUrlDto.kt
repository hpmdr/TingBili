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

/** 本次返回的全部 dash 音频轨（供音质面板展示可用性；服务端按登录/会员态过滤，未登录通常只有 64K/132K）。 */
fun PlayUrlDto.toAudioOptions(): List<DashAudio> =
    data?.dash?.audio?.filter { it.resolvedUrl().isNotBlank() }.orEmpty()

/**
 * 按偏好档位选轨（[pickAudio] 语义）：偏好 132K 则 132K/64K 中取 132K；
 * 偏好 Hi-Res 则取服务端给出的最高档。够不着偏好时自动回落，不断播。
 */
fun PlayUrlDto.selectAudio(preferCode: Int = AudioQuality.DEFAULT_CODE): DashAudio? =
    toAudioOptions().pickAudio(preferCode)

fun PlayUrlDto.toAudioUrl(preferCode: Int = AudioQuality.DEFAULT_CODE): String? {
    val dashUrl = selectAudio(preferCode)?.resolvedUrl()?.takeIf { it.isNotBlank() }
    if (!dashUrl.isNullOrBlank()) return dashUrl
    val durlUrl = data?.durl?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
    return durlUrl
}
