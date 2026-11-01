package cn.debubu.tingbili.feature.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.ui.formatDurationMs
import java.util.Locale

/**
 * 收藏夹 / BV 缓存页的 P 行。
 *
 * 布局分三栏，保证长标题不会把关键信息挤掉：
 * ```
 * [01]  1-20                                  2:08:31
 *       P1 · 无限流悬疑多人有声《请活到天明》连载最新
 * ```
 * - 左：序号（加载中转圈）
 * - 中：主标题（最多两行）+ 小字「P{序号} · BV 标题」（单行省略）
 * - 右：总时长固定右对齐，永远可见；再右是删除按钮
 *
 * 分 P 序号写在标题行最前面，BV 标题再长也只截断后半段，序号和时长都不受影响。
 */
@Composable
fun FavoriteTrackRow(
    index: Int,
    entity: FavoriteTrackEntity,
    played: HistoryEntity?,
    isPending: Boolean,
    focused: Boolean,
    playing: PlayingTrack?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    /** BV 收藏页顶部已显示 BV 标题，行内不再重复 */
    showVideoTitle: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else Color.Transparent
            )
            .clickable(enabled = !isPending) { onClick() }
            .padding(
                start = 16.dp,
                // 删除按钮自带 48dp 触控区，行尾不再留额外间距
                end = if (onDelete != null) 4.dp else 16.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPending) {
            Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        } else {
            Text(
                text = String.format(Locale.ROOT, "%02d", index + 1),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(32.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = entity.title.ifBlank { entity.bvid },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            trackMetaLabel(entity, showVideoTitle = showVideoTitle)?.let { meta ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (focused) {
                Spacer(Modifier.height(2.dp))
                if (playing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "正在播放 " + formatDurationMs(playing.positionMs),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    played?.let { record ->
                        Text(
                            text = "上次听到 " + formatDurationMs(record.positionMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 总时长固定在最右，长标题只会截断中间的小字
        entity.durationMs.takeIf { it > 0L }?.let { durationMs ->
            Text(
                text = formatDurationMs(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }

        if (onDelete != null) {
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * 列表小字：`P19 · BV 总标题`。
 * 单 P（BV 标题与分 P 同名）时省略 `P1` 和标题，只留空；标题为空时只显示序号。
 */
private fun trackMetaLabel(entity: FavoriteTrackEntity, showVideoTitle: Boolean): String? =
    cn.debubu.tingbili.core.ui.trackMetaLabel(
        title = entity.title,
        videoTitle = entity.videoTitle,
        pageIndex = entity.pageIndex,
        showVideoTitle = showVideoTitle,
    )
