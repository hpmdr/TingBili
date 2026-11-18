package cn.debubu.tingbili.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import cn.debubu.tingbili.data.bilibili.model.BiliComment
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 评论行共用件：主列表和楼中楼详情页都用它（对 PiliPlus reply_item_grpc.dart）。
 *
 * 只读：PiliPlus 那一长串交互（回复/点赞/翻译/查看对话/长按菜单）要 csrf + 登录态，
 * 等真要做再补；现在只抄展示结构——头部（头像/昵称/Lv/UP标/时间）+ 正文 + 点赞回复数
 * + 楼中楼预览盒（replyItemRow 同款：圆角灰底、名色高亮、": " 分隔、尾行可点）。
 */
@Composable
internal fun CommentRow(
    comment: BiliComment,
    upMid: Long,
    /** 详情页头部（楼主本身）不挂预览盒，PiliPlus 的 header 同样只有正文 */
    showPreview: Boolean = true,
    onOpenReplies: (BiliComment) -> Unit = {},
) {
    val imageFormat = LocalImageFormat.current
    val avatar = remember(comment.avatar, imageFormat) {
        comment.avatar.takeIf { it.isNotBlank() }?.biliImage(BiliImageVariant.Avatar, imageFormat)
    }
    val isUp = upMid > 0L && comment.mid == upMid
    val timeText = remember(comment.ctimeSec) { formatCommentTime(comment.ctimeSec) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        AsyncImage(
            model = avatar,
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = comment.uname.ifBlank { "已注销用户" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (comment.level > 0) {
                        Spacer(Modifier.width(4.dp))
                        CommentBadge(
                            text = "Lv${comment.level}",
                            container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            content = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (isUp) {
                        Spacer(Modifier.width(4.dp))
                        CommentBadge(
                            text = "UP",
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    if (comment.isTop) {
                        Spacer(Modifier.width(4.dp))
                        CommentBadge(
                            text = "置顶",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            CommentMessage(text = comment.message, key = comment.rpid)
            if (comment.like > 0 || comment.replyCount > 0) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (comment.like > 0) {
                        Icon(
                            imageVector = Icons.Outlined.ThumbUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = formatCommentCount(comment.like.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    if (comment.replyCount > 0) {
                        ReplyCount(
                            count = comment.replyCount,
                            onClick = { onOpenReplies(comment) },
                        )
                    }
                }
            }
            if (showPreview && comment.replies.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                NestedReplies(comment, onOpenReplies = onOpenReplies)
            }
        }
    }
}

/** 回复数：有楼中楼就可点进详情（对 PiliPlus 整行 InkWell 进 replyReply） */
@Composable
private fun ReplyCount(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = formatCommentCount(count.toLong()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 长评论默认折 8 行；实际溢出才允许点开，短评论点了没反应，避免误以为能交互 */
@Composable
internal fun CommentMessage(text: String, key: Long) {
    var expanded by remember(key) { mutableStateOf(false) }
    var overflow by remember(key) { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (!expanded && result.hasVisualOverflow != overflow) overflow = result.hasVisualOverflow
        },
        modifier = Modifier.clickable(enabled = overflow) { expanded = !expanded },
    )
}

/**
 * 楼中楼预览盒（抄 PiliPlus replyItemRow）：
 * 圆角灰底、每条最多 2 行、名字主色 + 行内 UP 标、直接回楼主用 ": "、楼中楼互回只空格
 * （B 站接口的互回正文自带 "回复 @xxx" 前缀，这里不再拼被回复人名）；
 * 尾行「共 X 条回复」主色可点，整盒可点，都是进详情。
 */
@Composable
internal fun NestedReplies(
    comment: BiliComment,
    onOpenReplies: (BiliComment) -> Unit = {},
) {
    val nameColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
            .clickable { onOpenReplies(comment) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        comment.replies.take(NESTED_PREVIEW_LIMIT).forEach { reply ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = nameColor)) {
                        append(reply.uname.ifBlank { "已注销用户" })
                    }
                    if (reply.mid == comment.mid && reply.mid != 0L) {
                        withStyle(SpanStyle(color = nameColor)) { append(" [UP]") }
                    }
                    // root == parent：直接回楼主，用 ": "；否则楼中楼互回，正文自带前缀，只空格
                    append(if (reply.root == reply.parent) ": " else " ")
                    withStyle(SpanStyle(color = bodyColor)) { append(reply.message) }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val shown = comment.replies.size.coerceAtMost(NESTED_PREVIEW_LIMIT)
        if (comment.replyCount > shown) {
            Text(
                text = "共 ${formatCommentCount(comment.replyCount.toLong())} 条回复",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun CommentBadge(
    text: String,
    container: Color,
    content: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(container)
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
internal fun CommentFooter(
    loadingMore: Boolean,
    appendError: String?,
    end: Boolean,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            appendError != null -> TextButton(onClick = onRetry) {
                Text("加载失败，点击重试", style = MaterialTheme.typography.labelSmall)
            }
            end -> Text(
                text = "没有更多评论了",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CommentErrorBlock(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/** 折叠时保留的行数：B 站长评论常见，超过就折起来 */
internal const val COLLAPSED_LINES = 8

/** 楼中楼最多预览几条（接口通常只回 3 条，留余量） */
internal const val NESTED_PREVIEW_LIMIT = 3

private val COMMENT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 相对时间：1 分钟内「刚刚」，1 天内按小时/分钟，30 天内按天，再早给日期 */
internal fun formatCommentTime(epochSec: Long): String {
    if (epochSec <= 0L) return ""
    val diffMs = System.currentTimeMillis() - epochSec * 1000L
    return when {
        diffMs < 60_000L -> "刚刚"
        diffMs < 3_600_000L -> "${diffMs / 60_000L}分钟前"
        diffMs < 86_400_000L -> "${diffMs / 3_600_000L}小时前"
        diffMs < 30L * 86_400_000L -> "${diffMs / 86_400_000L}天前"
        else -> try {
            Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate()
                .format(COMMENT_DATE_FORMATTER)
        } catch (_: Exception) {
            ""
        }
    }
}

internal fun formatCommentCount(n: Long): String = when {
    n >= 100_000_000L -> "%.1f亿".format(n / 100_000_000.0)
    n >= 10_000L -> "%.1f万".format(n / 10_000.0)
    else -> n.toString()
}
