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
 * 贴底 mini 播放条：全宽矩形、与底栏同色实底（视觉连成一体）+ 底边 3dp 进度线。
 * 层级只有三层：标题（主）→ 章节 + 等宽时间（次/辅）→ 圆形播放键（唯一强调色）。
 * - [visible] 为 false 时向上收起、不占位（滚动联动隐藏 + 手动关闭都走这里）；
 *   注：曾试过滚动只改位移以省重测，但会留一块底色死区（slot 占位不塌），
 *   实测不如收起干净，已回滚；滚动时的过渡重测仅 250ms，可接受。
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
    onDismiss: () -> Unit = {},
) {
    if (track == null) return
    val trackKey = "${track.bvid}:${track.cid}"
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
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
            modifier = modifier,
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
    modifier: Modifier = Modifier,
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
    // 滚动隐藏只改位移：Scaffold 内边距不动，内容不重测
    val scope = rememberCoroutineScope()
    // 切歌后复位（上滑关闭是按首歌记忆的）
    LaunchedEffect(trackKey) { offsetY.snapTo(0f) }
    val dismissThreshold = max(72f, barHeightPx * DISMISS_FRACTION)

    Surface(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .testTag("miniPlayerBar")
            .onSizeChanged { barHeightPx = it.height.toFloat() }
            .graphicsLayer {
                translationY = offsetY.value
                // 上滑（负位移）与滚隐（正位移）各自淡出，相乘叠加
                alpha = if (barHeightPx > 0f) {
                    (1f + offsetY.value / barHeightPx).coerceIn(0f, 1f)
                } else {
                    1f
                }
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
        shape = RectangleShape,
        // 与 M3 NavigationBar 默认容器同色，拼成一整块实底
        color = MaterialTheme.colorScheme.surfaceContainer,
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
