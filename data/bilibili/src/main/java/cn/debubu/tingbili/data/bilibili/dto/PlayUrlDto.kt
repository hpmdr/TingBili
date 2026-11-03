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
    val video: List<DashVideo>? = null,
    /**
     * 杜比伴音（30250/30255）：**不在** [audio] 里，服务端单独放这个节点。
     * 只有请求 fnval 含杜比位（见 `BiliApi.PLAYURL_FNVAL`）且账号有权益时才填充。
     */
    val dolby: DolbyData? = null,
    /** Hi-Res 无损（30251 FLAC）：单条而不是数组，同样有独立的节点。 */
    val flac: FlacData? = null
)

/** dash.dolby：type 1=普通杜比，2=全景杜比；audio 是伴音流列表（元素结构同 [DashAudio]）。 */
@Serializable
data class DolbyData(
    val type: Int = 0,
    val audio: List<DashAudio>? = null
)

/** dash.flac：display 控制播放器是否显示 Hi-Res 切换入口，audio 为唯一那条无损轨。 */
@Serializable
data class FlacData(
    val display: Boolean = false,
    val audio: DashAudio? = null
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

/**
 * 本次返回的全部可播音频轨（供音质面板展示可用性 + 播放选轨）。
 *
 * 杜比（30250/30255）与 Hi-Res（30251）不在 `dash.audio` 里，分别躺在 `dash.dolby.audio`
 * 和 `dash.flac.audio`；只读 `dash.audio` 会让大会员也永远看不到这两档（2026-11 反馈）。
 * 服务端仍按登录/会员态过滤：没权益时这两个节点为空，列表自然回落到 64K/132K/192K。
 */
fun PlayUrlDto.toAudioOptions(): List<DashAudio> {
    val dash = data?.dash ?: return emptyList()
    return buildList {
        addAll(dash.audio.orEmpty())
        addAll(dash.dolby?.audio.orEmpty())
        dash.flac?.audio?.let { add(it) }
    }.filter { it.resolvedUrl().isNotBlank() }
}

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
