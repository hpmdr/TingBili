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
    val pageCount: Int = 1
)
