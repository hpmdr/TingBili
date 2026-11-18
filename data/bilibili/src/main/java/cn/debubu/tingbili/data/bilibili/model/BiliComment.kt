package cn.debubu.tingbili.data.bilibili.model

/**
 * 评论排序：直接对应 `/x/v2/reply/main` 的 `mode` 参数。
 * 2 = 最新（按时间），3 = 热门（按热度）——与 PiliPlus 的 MAIN_LIST_TIME / MAIN_LIST_HOT 同源。
 */
enum class CommentSort(val mode: Int) {
    HOT(3),
    LATEST(2),
}

/**
 * 一页评论。游标分页：[nextOffset] 来自 `cursor.pagination_reply.next_offset`，
 * 下一页把它原样塞回 `pagination_str`；[isEnd] 为真表示没有更多了。
 */
data class CommentPage(
    val comments: List<BiliComment>,
    val total: Int,
    val nextOffset: String,
    val isEnd: Boolean,
    /** 接口回带的 UP 主 mid（可能为 0，调用方另有路由参数兜底） */
    val upMid: Long = 0,
)

/**
 * 一条评论的楼中楼详情页（抄 PiliPlus VideoReplyReplyPanel）。
 * [root] 是楼主：详情接口会把它一起带回，翻页只追加 [replies]；
 * [total] 是楼主的 `count`（相关回复总数），pn 翻页到底的判定用它。
 */
data class CommentDetailPage(
    val root: BiliComment,
    val replies: List<BiliComment>,
    val total: Int,
    val pageNum: Int,
    val isEnd: Boolean,
    /** 接口回带的 UP 主 mid（可能为 0，调用方另有路由参数兜底） */
    val upMid: Long = 0,
)

/**
 * 单条评论。[replies] 在主列表里只是接口顺带的楼中楼预览（通常前 3 条），
 * 点进详情页后另调 `/x/v2/reply/reply` 拉全量（抄 PiliPlus replyReplyList）。
 */
data class BiliComment(
    val rpid: Long,
    val mid: Long,
    val uname: String,
    val avatar: String,
    val level: Int,
    val message: String,
    val like: Int,
    val replyCount: Int,
    /** 发表时间（epoch 秒） */
    val ctimeSec: Long,
    /** 置顶评论（接口 top_replies 里的，已排在最前） */
    val isTop: Boolean = false,
    val replies: List<BiliComment> = emptyList(),
    /**
     * 楼中楼归属（抄 PiliPlus reply2reply 的 ReplyRoot：root/parent/dialog）。
     * 主列表预览里就有：`root == parent` 说明是直接回楼主，否则是楼中楼互回，
     * 预览分隔符就按这个切（": " 还是 " "，抄 reply_item_grpc.dart replyItemRow）。
     */
    val root: Long = 0,
    val parent: Long = 0,
    val dialog: Long = 0,
)
