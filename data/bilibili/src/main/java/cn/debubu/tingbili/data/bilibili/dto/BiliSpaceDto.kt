package cn.debubu.tingbili.data.bilibili.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val BVID_REGEX = Regex("BV[0-9A-Za-z]+")

fun extractBvid(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return BVID_REGEX.find(raw)?.value
}

// -- 收藏夹列表 GET /x/v3/fav/folder/created/list --

@Serializable
data class FavFolderDto(
    val code: Int = 0,
    val message: String = "",
    val data: FavFolderData? = null,
)

@Serializable
data class FavFolderData(
    val count: Int = 0,
    val list: List<FavFolderItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FavFolderItem(
    val id: Long = 0L,
    val title: String = "",
    val cover: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
)

// -- 夹内视频 GET /x/v3/fav/resource/list --

@Serializable
data class FavDetailDto(
    val code: Int = 0,
    val message: String = "",
    val data: FavDetailData? = null,
)

@Serializable
data class FavDetailData(
    val info: FavDetailInfo? = null,
    val medias: List<FavDetailMedia> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FavDetailInfo(
    val id: Long = 0L,
    val title: String = "",
)

@Serializable
data class FavDetailMedia(
    val id: Long = 0L,
    val type: Int = 2,
    val title: String = "",
    val cover: String = "",
    val bvid: String = "",
    @SerialName("bv_id") val bvId: String = "",
    val duration: Long = 0L,
    val upper: FavUpper? = null,
    val ogv: FavOgv? = null,
)

@Serializable
data class FavUpper(val name: String = "")

@Serializable
data class FavOgv(val title: String = "")

// -- 稍后看 GET /x/v2/history/toview/web(WBI) --

@Serializable
data class ToviewDto(
    val code: Int = 0,
    val message: String = "",
    val data: ToviewData? = null,
)

@Serializable
data class ToviewData(
    val count: Int = 0,
    val list: List<ToviewItem> = emptyList(),
)

@Serializable
data class ToviewItem(
    val aid: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val duration: Long = 0L,
    val progress: Long = 0L,
    val pubdate: Long = 0L,
    val owner: ToviewOwner? = null,
)

@Serializable
data class ToviewOwner(val name: String = "")

// -- 历史 GET /x/web-interface/history/cursor --

@Serializable
data class BiliHistoryDto(
    val code: Int = 0,
    val message: String = "",
    val data: BiliHistoryData? = null,
)

@Serializable
data class BiliHistoryData(
    val list: List<BiliHistoryItem> = emptyList(),
)

@Serializable
data class BiliHistoryItem(
    val title: String = "",
    val cover: String = "",
    val uri: String = "",
    @SerialName("author_name") val authorName: String = "",
    @SerialName("view_at") val viewAt: Long = 0L,
    val progress: Long = 0L,
    val duration: Long = 0L,
    val badge: String = "",
    @SerialName("show_title") val showTitle: String = "",
    val history: HistoryRef = HistoryRef(),
)

@Serializable
data class HistoryRef(
    val oid: Long = 0L,
    val business: String = "",
)

// -- 对 UI 的领域模型(只读) --

data class BiliFavFolder(
    val id: Long,
    val title: String,
    val cover: String,
    val count: Int,
)

data class BiliSpaceVideo(
    val bvid: String,
    val title: String,
    val cover: String,
    val durationSec: Long,
    val upper: String,
    val playable: Boolean = true,
    val tag: String = "",
)

fun FavFolderDto.toFolders(): Pair<List<BiliFavFolder>, Boolean> {
    val d = data ?: return emptyList<BiliFavFolder>() to false
    return d.list.map {
        BiliFavFolder(it.id, it.title.ifBlank { "未命名收藏夹" }, it.cover.normalizeBiliImageUrl(), it.mediaCount)
    } to d.hasMore
}

fun FavDetailDto.toSpaceVideos(): Pair<List<BiliSpaceVideo>, Boolean> {
    val d = data ?: return emptyList<BiliSpaceVideo>() to false
    return d.medias.map { m ->
        val bvid = m.bvid.ifBlank { m.bvId }
        val playable = bvid.isNotBlank() && m.type == 2 && m.ogv == null
        BiliSpaceVideo(
            bvid = bvid,
            title = m.title.ifBlank { "已失效视频" },
            cover = m.cover.normalizeBiliImageUrl(),
            durationSec = m.duration,
            upper = m.upper?.name.orEmpty(),
            playable = playable,
            tag = if (playable) "" else "非视频",
        )
    } to d.hasMore
}

fun ToviewDto.toSpaceVideos(): List<BiliSpaceVideo> =
    data?.list.orEmpty().map { item ->
        BiliSpaceVideo(
            bvid = item.bvid,
            title = item.title.ifBlank { item.bvid },
            cover = item.pic.normalizeBiliImageUrl(),
            durationSec = item.duration,
            upper = item.owner?.name.orEmpty(),
            playable = item.bvid.isNotBlank(),
        )
    }

fun BiliHistoryDto.toSpaceVideos(): List<BiliSpaceVideo> =
    data?.list.orEmpty().map { item ->
        val bvid = extractBvid(item.uri).orEmpty()
        val isArchive = item.history.business.isBlank() || item.history.business == "archive"
        BiliSpaceVideo(
            bvid = bvid,
            title = item.title.ifBlank { item.showTitle.ifBlank { bvid } },
            cover = item.cover.normalizeBiliImageUrl(),
            durationSec = item.duration,
            upper = item.authorName,
            playable = bvid.isNotBlank() && isArchive,
            tag = if (bvid.isNotBlank() && !isArchive) item.history.business else "",
        )
    }
