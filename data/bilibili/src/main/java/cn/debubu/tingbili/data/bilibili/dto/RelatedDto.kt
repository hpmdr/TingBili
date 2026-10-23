package cn.debubu.tingbili.data.bilibili.dto

import cn.debubu.tingbili.core.data.model.Track
import kotlinx.serialization.Serializable

// -- 相关推荐 GET /x/web-interface/archive/related?bvid=xxx --
// 对标 PiliPlus relatedVideoList：只传 bvid，无需 WBI 签名、无需登录，
// 一次返回约 10~20 条，无分页参数。

@Serializable
data class RelatedDto(
    val code: Int = 0,
    val message: String = "",
    val data: List<RelatedItem> = emptyList(),
)

@Serializable
data class RelatedItem(
    val aid: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** 时长（秒，接口是 Int） */
    val duration: Long = 0L,
    /** 分 P 数量 */
    val videos: Int = 1,
    val owner: RelatedOwner? = null,
)

@Serializable
data class RelatedOwner(
    val mid: Long = 0L,
    val name: String = "",
)

fun RelatedDto.toTracks(): List<Track> =
    data.filter { it.bvid.isNotBlank() }.map { item ->
        Track(
            bvid = item.bvid,
            cid = 0L,
            title = item.title,
            author = item.owner?.name ?: "",
            cover = item.pic.normalizeBiliImageUrl(),
            durationMs = item.duration * 1000L,
            subtitleUrl = null,
            pageCount = item.videos.coerceAtLeast(1)
        )
    }
