package cn.debubu.tingbili.data.bilibili.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- UP 主信息 GET /x/space/wbi/acc/info (WBI) --

@Serializable
data class SpaceInfoDto(
    val code: Int = 0,
    val message: String = "",
    val data: SpaceInfoData? = null,
)

@Serializable
data class SpaceInfoData(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
    val sign: String = "",
    val level: Int = 0,
    val official: SpaceOfficial? = null,
    val birthday: String = "",
    val school: SpaceSchool? = null,
    val profession: SpaceProfession? = null,
)

/** 官方认证：role=0 表示无认证，title 如「bilibili 知名UP主」 */
@Serializable
data class SpaceOfficial(
    val role: Int = 0,
    val title: String = "",
    val desc: String = "",
    val type: Int = 0,
)

@Serializable
data class SpaceSchool(val name: String = "")

@Serializable
data class SpaceProfession(val name: String = "")

// -- 粉丝数 GET /x/relation/stat --

@Serializable
data class RelationStatDto(
    val code: Int = 0,
    val message: String = "",
    val data: RelationStatData? = null,
)

@Serializable
data class RelationStatData(
    val mid: Long = 0L,
    val follower: Long = 0L,
    val following: Long = 0L,
)

// -- 投稿列表 GET /x/space/wbi/arc/search (WBI)，order: pubdate/click/stow --

@Serializable
data class SpaceVideosDto(
    val code: Int = 0,
    val message: String = "",
    val data: SpaceVideosData? = null,
)

@Serializable
data class SpaceVideosData(
    val list: SpaceVideosList? = null,
    val page: SpaceVideosPage? = null,
)

@Serializable
data class SpaceVideosList(
    val vlist: List<SpaceVideoItem> = emptyList(),
)

@Serializable
data class SpaceVideosPage(
    val count: Int = 0,
    val pn: Int = 0,
    val ps: Int = 0,
)

@Serializable
data class SpaceVideoItem(
    val aid: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val description: String = "",
    /** 时长字符串（"mm:ss" / "hh:mm:ss"）；该接口不返回秒数，别用 duration 字段 */
    val length: String = "",
    /** 发布时间（epoch 秒）；接口字段名是 created，不是 pubdate */
    @SerialName("created") val created: Long = 0L,
    /** 播放量 */
    val play: Long = 0L,
    /** 弹幕数 */
    @SerialName("video_review") val danmaku: Long = 0L,
    val author: String = "",
)

/** "03:15" / "1:02:03" → 秒 */
private fun parseLengthToSec(raw: String): Long {
    if (raw.isBlank()) return 0L
    var sec = 0L
    raw.split(":").forEach { part -> sec = sec * 60 + (part.toLongOrNull() ?: 0L) }
    return sec
}

// -- 对 UI 的领域模型 --

/** UP 主空间头部信息（信息 + 粉丝关注数各一次请求，社交数据拉不到时降级为 0） */
data class UpSpaceInfo(
    val mid: Long,
    val name: String,
    val face: String,
    val sign: String,
    val fans: Long,
    val following: Long = 0L,
    /** 等级 0~6 */
    val level: Int = 0,
    /** 官方认证标题，空串 = 未认证 */
    val officialTitle: String = "",
    val birthday: String = "",
    val school: String = "",
    val profession: String = "",
    /** 投稿总数，来自投稿列表的 page.count */
    val archiveCount: Int = 0,
)

/** UP 投稿视频（列表项） */
data class UpVideo(
    val bvid: String,
    val title: String,
    val cover: String,
    val durationSec: Long,
    val play: Long,
    val pubdateSec: Long,
)

fun SpaceInfoDto.toSpaceInfo(fans: Long = 0L, following: Long = 0L): UpSpaceInfo? {
    val d = data ?: return null
    return UpSpaceInfo(
        mid = d.mid,
        name = d.name.ifBlank { "UP 主" },
        face = d.face.normalizeBiliImageUrl(),
        sign = d.sign,
        fans = fans,
        following = following,
        level = d.level,
        officialTitle = d.official?.title.orEmpty(),
        birthday = d.birthday.orEmpty(),
        school = d.school?.name.orEmpty(),
        profession = d.profession?.name.orEmpty(),
    )
}

fun SpaceVideosDto.toUpVideos(): Pair<List<UpVideo>, Int> {
    val d = data ?: return emptyList<UpVideo>() to 0
    val videos = d.list?.vlist.orEmpty().map { item ->
        UpVideo(
            bvid = item.bvid,
            title = item.title.ifBlank { item.bvid },
            cover = item.pic.normalizeBiliImageUrl(),
            durationSec = parseLengthToSec(item.length),
            play = item.play,
            pubdateSec = item.created,
        )
    }
    return videos to (d.page?.count ?: 0)
}
