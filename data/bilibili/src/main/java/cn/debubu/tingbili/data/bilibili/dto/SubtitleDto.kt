package cn.debubu.tingbili.data.bilibili.dto

import cn.debubu.tingbili.data.bilibili.model.LyricLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `/x/player/wbi/v2` 返回体。
 *
 * 这一步**只列出可用字幕轨**（`subtitle.subtitles[]`），正文不在这里，
 * 必须再用每条轨的 `subtitle_url` 去 CDN 下载一份 `{"body":[...]}` 文件。
 * 以前这里直接 `toLyricLines()` 拿不到任何东西，就是漏了这一跳。
 */
@Serializable
data class SubtitleDto(
    val code: Int = 0,
    val message: String = "",
    val data: SubtitleData? = null
)

@Serializable
data class SubtitleData(
    @SerialName("subtitle") val subtitle: SubtitleInfo? = null
)

@Serializable
data class SubtitleInfo(
    @SerialName("lan") val lan: String = "",
    @SerialName("lan_doc") val lanDoc: String = "",
    @SerialName("subtitles") val subtitles: List<SubtitleItem> = emptyList()
)

@Serializable
data class SubtitleItem(
    @SerialName("id") val id: Long = 0L,
    @SerialName("lan") val lan: String = "",
    @SerialName("lan_doc") val lanDoc: String = "",
    @SerialName("subtitle_url") val subtitleUrl: String = "",
    @SerialName("subtitle_url_v2") val subtitleUrlV2: String = "",
    /**
     * B 站用 `type == 1` 标记「AI 生成字幕」。
     * 注意不是 `ai_status`——那个字段在返回里并不参与判断。
     */
    @SerialName("type") val type: Int = 0
)

/** `subtitle_url` 下载回来的字幕正文文件 */
@Serializable
data class SubtitleFile(
    @SerialName("body") val body: List<SubtitleBodyItem> = emptyList()
)

@Serializable
data class SubtitleBodyItem(
    @SerialName("from") val from: Double = 0.0,
    @SerialName("to") val to: Double = 0.0,
    @SerialName("content") val content: String = "",
    @SerialName("sid") val sid: Long = 0L,
    @SerialName("location") val location: Int = 2,
    @SerialName("music") val music: Double = 0.0
)

/** 归一化后的字幕轨，[url] 已是可以直接请求的绝对地址 */
data class SubtitleTrack(
    val lan: String,
    val lanDoc: String,
    val url: String,
    val isAi: Boolean,
) {
    /** 给 UI 看的名称，AI 字幕标出来 */
    val displayLabel: String get() = if (isAi) "$lanDoc（AI）" else lanDoc
}

/**
 * B 站的 `subtitle_url` 是协议相对地址（`//aisubtitle.hdslb.com/...`），
 * 原样丢给 OkHttp 会解析失败，得先补 scheme。
 */
fun String.normalizeSubtitleUrl(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") -> replaceFirst("http://", "https://")
    else -> this
}

/**
 * 列出可用字幕轨并排好优先级：
 * 1. 中文优先（`lan` 含 `zh`）
 * 2. 同语言下人工字幕优先于 AI 字幕（`type != 1`）
 *
 * 调用方取 [List.firstOrNull] 即为最佳轨。
 */
fun SubtitleDto.toTracks(): List<SubtitleTrack> =
    (data?.subtitle?.subtitles ?: emptyList())
        .mapNotNull { item ->
            // subtitle_url_v2 是官方补充字段，作为主字段缺失时的兜底
            val url = item.subtitleUrl.ifBlank { item.subtitleUrlV2 }.normalizeSubtitleUrl()
            if (url.isBlank()) null else SubtitleTrack(item.lan, item.lanDoc, url, item.type == 1)
        }
        .sortedWith(
            compareByDescending<SubtitleTrack> { it.lan.contains("zh") }
                .thenBy { it.isAi }
        )

/** 字幕正文 → 逐行歌词；空行丢掉，按开始时间升序 */
fun SubtitleFile.toLyricLines(): List<LyricLine> =
    body.asSequence()
        .filter { it.content.isNotBlank() }
        .sortedBy { it.from }
        .map { LyricLine((it.from * 1000).toLong(), it.content.trim()) }
        .toList()