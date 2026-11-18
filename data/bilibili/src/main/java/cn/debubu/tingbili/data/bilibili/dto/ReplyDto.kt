package cn.debubu.tingbili.data.bilibili.dto

import cn.debubu.tingbili.data.bilibili.model.BiliComment
import cn.debubu.tingbili.data.bilibili.model.CommentDetailPage
import cn.debubu.tingbili.data.bilibili.model.CommentPage
import kotlinx.serialization.Serializable

/**
 * `/x/v2/reply/main` 的返回（扁平版，只摘界面要用的字段）。
 *
 * 为什么用 main 而不是老的 `/x/v2/reply`：老接口的 `sort` 参数现在无论传什么都按热度返回
 * （`mode` 恒为 3，传 0 直接空列表），拿不到「最新」；main 用 `mode` + 游标，两种排序都在。
 */
@Serializable
data class ReplyMainDto(
    val code: Int = 0,
    val message: String = "",
    val data: ReplyMainData? = null,
)

@Serializable
data class ReplyMainData(
    val cursor: ReplyCursor? = null,
    val replies: List<ReplyItemDto>? = null,
    /** 置顶评论（含 UP 主置顶），需要单独排在列表最前 */
    val top_replies: List<ReplyItemDto>? = null,
    /** UP 主信息，`mid` 用于给评论打 UP 标（部分接口返回 0，调用方另有兜底） */
    val upper: ReplyUpper? = null,
)

@Serializable
data class ReplyCursor(
    val is_end: Boolean = false,
    val all_count: Int = 0,
    val pagination_reply: ReplyPagination? = null,
)

@Serializable
data class ReplyPagination(
    val next_offset: String = "",
)

@Serializable
data class ReplyUpper(
    val mid: Long = 0,
)

@Serializable
data class ReplyItemDto(
    val rpid: Long = 0,
    /** 楼中楼归属（抄 PiliPlus ReplyRoot：root/parent/dialog，主列表预览里同样有） */
    val root: Long = 0,
    val parent: Long = 0,
    val dialog: Long = 0,
    val mid: Long = 0,
    /** 根评论：回复数（与 rcount 口径不同，取有值的那个）；楼中楼通常为 0 */
    val count: Int = 0,
    val rcount: Int = 0,
    val like: Int = 0,
    /** 发表时间（epoch 秒） */
    val ctime: Long = 0,
    val member: ReplyMemberDto? = null,
    val content: ReplyContentDto? = null,
    /** 楼中楼预览：接口只回前几条，不代表全部 */
    val replies: List<ReplyItemDto>? = null,
)

@Serializable
data class ReplyMemberDto(
    /** 注意：接口这里是字符串，不是数字 */
    val mid: String = "",
    val uname: String = "",
    val avatar: String = "",
    val level_info: ReplyLevelInfoDto? = null,
)

@Serializable
data class ReplyLevelInfoDto(
    val current_level: Int = 0,
)

@Serializable
data class ReplyContentDto(
    val message: String = "",
)

/**
 * 置顶优先、按 rpid 去重（置顶评论偶尔也会出现在 replies 里），再映射成 UI 模型。
 * 头像可能是 `//i0.hdslb.com/...` 或 http 明文，统一归一化成 https。
 */
fun ReplyMainDto.toCommentPage(): CommentPage {
    val data = data ?: return CommentPage(emptyList(), 0, "", true)
    val seen = HashSet<Long>()
    val comments = ArrayList<BiliComment>()
    fun add(item: ReplyItemDto, isTop: Boolean = false) {
        if (item.rpid != 0L && !seen.add(item.rpid)) return
        comments += item.toComment(isTop)
    }
    data.top_replies.orEmpty().forEach { add(it, isTop = true) }
    data.replies.orEmpty().forEach { add(it) }

    val cursor = data.cursor
    val nextOffset = cursor?.pagination_reply?.next_offset.orEmpty()
    return CommentPage(
        comments = comments,
        total = cursor?.all_count ?: 0,
        nextOffset = nextOffset,
        isEnd = cursor?.is_end == true || nextOffset.isBlank(),
        upMid = data.upper?.mid ?: 0L,
    )
}

private fun ReplyItemDto.toComment(isTop: Boolean = false): BiliComment {
    val member = member
    return BiliComment(
        rpid = rpid,
        mid = member?.mid?.toLongOrNull() ?: mid,
        uname = member?.uname.orEmpty(),
        avatar = member?.avatar.orEmpty().normalizeBiliImageUrl(),
        level = member?.level_info?.current_level ?: 0,
        message = content?.message.orEmpty(),
        like = like,
        replyCount = count.takeIf { it > 0 } ?: rcount,
        ctimeSec = ctime,
        isTop = isTop,
        replies = replies.orEmpty().map { it.toComment() },
        root = root,
        parent = parent,
        dialog = dialog,
    )
}

/**
 * `/x/v2/reply/reply` 的返回（抄 PiliPlus ReplyHttp.replyReplyList + ReplyReplyData）。
 * 游客态可读：oid + root + pn + type + sort，不带 csrf。
 */
@Serializable
data class ReplyDetailDto(
    val code: Int = 0,
    val message: String = "",
    val data: ReplyDetailData? = null,
)

@Serializable
data class ReplyDetailData(
    val page: ReplyDetailPageDto? = null,
    /** UP 主信息，`mid` 用于给评论打 UP 标（部分接口返回 0，调用方另有兜底） */
    val upper: ReplyUpper? = null,
    /** 楼主：详情页头部用它，翻页不重复拉 */
    val root: ReplyItemDto? = null,
    /** 当前页的楼中楼，按 pn 追加 */
    val replies: List<ReplyItemDto>? = null,
)

@Serializable
data class ReplyDetailPageDto(
    val num: Int = 1,
    val size: Int = 20,
    val count: Int = 0,
)

/**
 * root 缺失（被删）时返回 null，调用方转成「评论已删除」错误，
 * 对应 PiliPlus 的 jumpToDialogue 失败 toast（reply_item_grpc.dart）。
 */
fun ReplyDetailDto.toCommentDetailPage(): CommentDetailPage? {
    val data = data ?: return null
    val root = data.root?.toComment() ?: return null
    val replies = data.replies.orEmpty().map { it.toComment() }
    val page = data.page
    val total = page?.count ?: root.replyCount
    val end = replies.isEmpty() || (page != null && page.num * page.size >= page.count)
    return CommentDetailPage(
        root = root,
        replies = replies,
        total = total,
        pageNum = page?.num ?: 1,
        isEnd = end,
        upMid = data.upper?.mid ?: 0L,
    )
}
