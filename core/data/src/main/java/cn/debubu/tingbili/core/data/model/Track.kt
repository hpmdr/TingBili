package cn.debubu.tingbili.core.data.model

data class Track(
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val cover: String,
    val durationMs: Long,
    val subtitleUrl: String?
)
