package cn.debubu.tingbili.core.data.model

import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.BvFavoritePageEntity
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity

/**
 * BV 收藏的中立输入模型。
 *
 * `core:data` 不依赖 `data:bilibili`，所以 Repository 不收 `ViewData`；
 * 由 `data:bilibili` 的 `ViewData.toFavoriteSnapshot()` 在调用侧转换。
 */
data class BvFavoriteSnapshot(
    val bvid: String,
    val title: String,
    val author: String,
    val authorMid: Long?,
    val cover: String,
    val durationMs: Long,
    val pages: List<BvFavoritePageSnapshot>,
)

data class BvFavoritePageSnapshot(
    val cid: Long,
    val pageIndex: Int,
    val title: String,
    val durationMs: Long,
)

data class AddTracksResult(
    val added: Int,
    val skipped: Int,
)

fun FavoriteTrackEntity.toTrack(): Track = Track(
    bvid = bvid,
    cid = cid,
    title = title,
    author = author,
    cover = cover,
    durationMs = durationMs,
    subtitleUrl = null,
    videoTitle = videoTitle,
    pageIndex = pageIndex,
)

fun BvFavoritePageEntity.toTrack(parent: BvFavoriteEntity): Track = Track(
    bvid = parent.bvid,
    cid = cid,
    title = title,
    author = parent.author,
    cover = parent.cover,
    durationMs = durationMs,
    subtitleUrl = null,
    pageCount = parent.pageCount,
    videoTitle = parent.title,
    pageIndex = pageIndex,
    upMid = parent.authorMid,
)
