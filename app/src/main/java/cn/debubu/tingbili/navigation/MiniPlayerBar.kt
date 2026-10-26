package cn.debubu.tingbili.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.chapterLabel
import cn.debubu.tingbili.core.ui.formatDurationMs
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * mini 播放条：底边 3dp 进度线，层级只有三层：标题（主）→ 章节 + 等宽时间（次/辅）
 * → 圆形播放键（唯一强调色）。
 * - [visible] 只管有无（无曲目/手动关闭才变，滚动时不变）；
 * - [scrollVisible] 只做位移+淡出，不改布局：内容内边距全程不动，滑到底不弹；
 * - [floating] 悬浮卡片样式：16dp 圆角 + 阴影，盖在内容上，列表从它后面滚过；
 *   为 false 时是贴底全宽矩形（与底栏连成一体）。
 * - 条上上滑超过阈值（或快速上甩）触发 [onDismiss]，关闭≠暂停，音乐继续播，
 *   切歌后自动回来；
 * - 无曲目时由调用方隐藏（不占位）。
 */
@Composable
fun MiniPlayerBar(
    track: Track?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    visible: Boolean = true,
    scrollVisible: Boolean = true,
    floating: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    if (track == null) return
    val trackKey = "${track.bvid}:${track.cid}"
    // modifier 必须挂在最外层：它是 Box 的直接孩子，align 等 ParentData 才有效；
    // 挂进里面会被 AnimatedVisibility 隔断，对齐丢失、掉到顶部。
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        modifier = modifier,
    ) {
        DismissableBarContent(
            track = track,
            trackKey = trackKey,
            isPlaying = isPlaying,
            isLoading = isLoading,
            positionMs = positionMs,
            durationMs = durationMs,
            onToggle = onToggle,
            onOpen = onOpen,
            onDismiss = onDismiss,
            scrollVisible = scrollVisible,
            floating = floating,
        )
    }
}

@Composable
private fun DismissableBarContent(
    track: Track,
    trackKey: String,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    scrollVisible: Boolean = true,
    floating: Boolean = false,
) {
    val imageFormat = LocalImageFormat.current
    // 槽位每 500ms 跟进度重组一次：URL 与文案缓存，不重复拼串
    val coverUrl = remember(track.cover, imageFormat) {
        track.cover.biliImage(BiliImageVariant.ListThumb, imageFormat)
    }
    val title = remember(track) {
        track.videoTitle?.takeIf { it.isNotBlank() } ?: track.title.ifBlank { track.bvid }
    }
    val subtitle = remember(track) { chapterLabel(track.title, track.pageIndex, track.pageCount) }
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val timeText = remember(positionMs, durationMs) {
        if (durationMs > 0L) "${formatDurationMs(positionMs)} / ${formatDurationMs(durationMs)}" else null
    }
    val offsetY = remember { Animatable(0f) }
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    // 滚动隐藏只改位移：Scaffold 内边距不动，内容不重测、不弹跳
    val scrollOffsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 切歌后复位（上滑关闭是按首歌记忆的）
    LaunchedEffect(trackKey) {
        offsetY.snapTo(0f)
        scrollOffsetY.snapTo(0f)
    }
    // 滚动显隐：下滑到底也不改布局，只把条子滑到底栏后面并淡出
    LaunchedEffect(scrollVisible, barHeightPx) {
        if (barHeightPx > 0f) {
            if (scrollVisible) {
                scrollOffsetY.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(250))
            } else {
                scrollOffsetY.animateTo(barHeightPx, animationSpec = androidx.compose.animation.core.tween(250))
            }
        } else {
            scrollOffsetY.snapTo(0f)
        }
    }
    val dismissThreshold = max(72f, barHeightPx * DISMISS_FRACTION)

    Surface(
        onClick = onOpen,
        enabled = scrollVisible,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("miniPlayerBar")
            .onSizeChanged { barHeightPx = it.height.toFloat() }
            .graphicsLayer {
                translationY = offsetY.value + scrollOffsetY.value
                // 上滑关闭（负位移）与滚动隐藏（正位移）各自淡出，相乘叠加
                val dragAlpha = if (barHeightPx > 0f) {
                    (1f + offsetY.value / barHeightPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val scrollAlpha = if (barHeightPx > 0f) {
                    (1f - scrollOffsetY.value / barHeightPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
                alpha = dragAlpha * scrollAlpha
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        offsetY.snapTo((offsetY.value + delta).coerceAtMost(0f))
                    }
                },
                onDragStopped = { velocity ->
                    scope.launch {
                        if (offsetY.value < -dismissThreshold || velocity < -FLING_VELOCITY_PX_S) {
                            offsetY.animateTo(-max(barHeightPx, 1f))
                            onDismiss()
                        } else {
                            offsetY.animateTo(0f)
                        }
                    }
                },
            ),
        shape = if (floating) RoundedCornerShape(16.dp) else RectangleShape,
        // 悬浮时与内容分离：圆角卡片 + 阴影；贴底时与底栏同色连成一体
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = if (floating) 3.dp else 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
                // 层级：标题（主）> 章节（次）> 时间（辅，等宽数字）
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = subtitle,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (timeText != null) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                                maxLines = 1,
                                modifier = Modifier.testTag("miniTime"),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                // 固定 48dp 槽位：加载/播放切换不改变文字宽度，避免布局跳动
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .testTag("miniLoading"),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        FilledTonalIconButton(
                            onClick = onToggle,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("miniPlayPause"),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            // 进度线落在底边、与内容左右对齐：读作"时间轴"，而不是条顶的分隔线
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .testTag("miniProgress"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                strokeCap = StrokeCap.Round,
                // M3 默认在轨道末端画 4dp stop 圆点，细线上像一颗"脏点"，去掉
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(7.dp))
        }
    }
}

private const val DISMISS_FRACTION = 0.35f
private const val FLING_VELOCITY_PX_S = 500f
