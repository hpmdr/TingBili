package cn.debubu.tingbili.core.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val cover: String,
    val durationMs: Long,
    val subtitleUrl: String?,
    /** 分 P 数量，多 P 视频大于 1（搜索/详情接口返回） */
    val pageCount: Int = 1,
    /**
     * BV 视频总标题（合集名）。分 P 的 [title] 常是 UP 主随手起的名字（如 "M 1-30"），
     * 只有它能回答"在听哪本书/哪个合集"。旧数据（播放队列快照、听单）可能为 null。
     */
    val videoTitle: String? = null,
    /** 第几个分 P（从 1 开始，接口的 page 字段）；单 P 或未知时为 null */
    val pageIndex: Int? = null
)
