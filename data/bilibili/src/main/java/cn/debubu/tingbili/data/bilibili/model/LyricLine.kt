package cn.debubu.tingbili.data.bilibili.model

import kotlinx.serialization.Serializable

/** 磁盘快照要整体 JSON 存（见 SubtitleCache），故可序列化 */
@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String
)
