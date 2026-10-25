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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.chapterLabel
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 贴底 mini 播放条：全宽矩形、与底栏同色实底（视觉连成一体）+ 顶部 2dp 播放进度线。
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
    progress: Float = 0f,
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
            progress = progress,
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
    progress: Float,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageFormat = LocalImageFormat.current
    // 槽位每 500ms 跟进度重组一次：URL 与文案缓存，不重复拼串
    val coverUrl = remember(track.cover, imageFormat) {
        track.cover.biliImage(BiliImageVariant.MediumSquare, imageFormat)
    }
    val title = remember(track) {
        track.videoTitle?.takeIf { it.isNotBlank() } ?: track.title.ifBlank { track.bvid }
    }
    val subtitle = remember(track) { chapterLabel(track.title, track.pageIndex, track.pageCount) }
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
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("miniProgress"),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("miniLoading"),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = onToggle,
                        modifier = Modifier.testTag("miniPlayPause"),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private const val DISMISS_FRACTION = 0.35f
private const val FLING_VELOCITY_PX_S = 500f
