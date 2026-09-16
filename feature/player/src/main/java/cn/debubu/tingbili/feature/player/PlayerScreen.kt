package cn.debubu.tingbili.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.chapterLabel
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@Composable
fun PlayerScreen(
    onBack: () -> Unit = {},
    vm: PlayerViewModel = hiltViewModel()
) {
    val imageFormat = LocalImageFormat.current
    val s by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 顶栏=合集名（“在听哪本书”）；章节行在封面下方，显示 “P1 · 章节名”
    val headerTitle = playerHeaderTitle(s.track)

    LaunchedEffect(s.currentLyricIndex) {
        if (s.currentLyricIndex >= 0) {
            listState.animateScrollToItem(s.currentLyricIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerTopBar(
            title = headerTitle,
            speed = s.speed,
            onSpeedClick = vm::cycleSpeed,
            speedEnabled = !s.isLoading,
            onBack = onBack,
        )

        Spacer(Modifier.height(12.dp))

        AsyncImage(
            model = s.track?.cover
                ?.takeIf { it.isNotBlank() }
                ?.biliImage(BiliImageVariant.SquareCover, imageFormat),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .widthIn(max = 320.dp)
                .aspectRatio(1f)
                .shadow(18.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp)),
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = chapterLabel(s.track?.title, s.track?.pageIndex, s.track?.pageCount ?: 1),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = s.track?.author.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        s.errorMessage?.let { errorMsg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        if (s.isLoading) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "正在加载...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(s.lyrics) { idx, line ->
                val isCurrent = idx == s.currentLyricIndex
                Text(
                    text = line.text,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (isCurrent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !s.isLoading) { vm.seekToLyric(line) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        PlaybackProgressBar(
            positionMs = s.positionMs,
            bufferedPositionMs = s.bufferedPositionMs,
            durationMs = s.durationMs,
            enabled = !s.isLoading,
            onSeek = vm::seekTo,
        )

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.previous() }, enabled = !s.isLoading) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.size(30.dp),
                )
            }

            IconButton(onClick = { vm.skipBackward() }, enabled = !s.isLoading) {
                SkipIcon(
                    seconds = s.stepSec,
                    forward = false,
                    contentDescription = "后退${s.stepSec}秒",
                )
            }

            if (s.isLoading) {
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
            } else {
                FilledIconButton(
                    onClick = { vm.toggle() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (s.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            IconButton(onClick = { vm.skipForward() }, enabled = !s.isLoading) {
                SkipIcon(
                    seconds = s.stepSec,
                    forward = true,
                    contentDescription = "前进${s.stepSec}秒",
                )
            }

            IconButton(onClick = { vm.next() }, enabled = !s.isLoading) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SkipIcon(
    seconds: Int,
    forward: Boolean,
    contentDescription: String,
) {
    Box(
        modifier = Modifier.size(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (forward) Icons.Default.Refresh else Icons.Default.Replay,
            contentDescription = contentDescription,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = seconds.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = 1.dp),
        )
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    speed: Float,
    onSpeedClick: () -> Unit,
    speedEnabled: Boolean,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
            )
        }

        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            // 长标题自适应：14sp~22sp 逐档缩放，最多两行，仍放不下才省略
            autoSize = TextAutoSize.StepBased(
                minFontSize = 14.sp,
                maxFontSize = 22.sp,
                stepSize = 1.sp,
            ),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 64.dp, vertical = 6.dp),
        )

        TextButton(
            onClick = onSpeedClick,
            enabled = speedEnabled,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Text("${speed}x", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaybackProgressBar(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var dragRatio by remember { mutableStateOf<Float?>(null) }
    val safeDuration = durationMs.coerceAtLeast(0L)
    val positionRatio = ratio(positionMs, safeDuration)
    val bufferedRatio = ratio(bufferedPositionMs, safeDuration).coerceAtLeast(positionRatio)
    val shownRatio = (dragRatio ?: positionRatio).coerceIn(0f, 1f)
    val shownPositionMs = if (dragRatio != null) (shownRatio * safeDuration).toLong() else positionMs
    val remainingMs = (safeDuration - shownPositionMs).coerceAtLeast(0L)

    val activeColor = MaterialTheme.colorScheme.primary
    val bufferedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
    val sliderColors = SliderDefaults.colors(
        thumbColor = activeColor,
        activeTrackColor = Color.Transparent,
        inactiveTrackColor = Color.Transparent,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${formatMs(shownPositionMs)} / ${formatMs(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "-${formatMs(remainingMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Slider(
            value = shownRatio,
            onValueChange = { if (enabled) dragRatio = it },
            onValueChangeFinished = {
                dragRatio?.let { onSeek((it * safeDuration).toLong()) }
                dragRatio = null
            },
            enabled = enabled && safeDuration > 0L,
            colors = sliderColors,
            track = { state ->
                val progress = state.value.coerceIn(0f, 1f)
                val buffered = bufferedRatio.coerceAtLeast(progress).coerceIn(0f, 1f)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp),
                ) {
                    val centerY = size.height / 2f
                    val baseHeight = 4.dp.toPx()
                    val activeHeight = 5.dp.toPx()

                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, centerY - baseHeight / 2f),
                        size = Size(size.width, baseHeight),
                        cornerRadius = CornerRadius(baseHeight / 2f),
                    )
                    drawRoundRect(
                        color = bufferedColor,
                        topLeft = Offset(0f, centerY - baseHeight / 2f),
                        size = Size(size.width * buffered, baseHeight),
                        cornerRadius = CornerRadius(baseHeight / 2f),
                    )
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, centerY - activeHeight / 2f),
                        size = Size(size.width * progress, activeHeight),
                        cornerRadius = CornerRadius(activeHeight / 2f),
                    )
                }
            },
            thumb = { state ->
                val scale by animateFloatAsState(
                    targetValue = if (state.isDragging) 1.2f else 1f,
                    label = "progressThumbScale",
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .shadow(6.dp, CircleShape, clip = false)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(3.dp, activeColor, CircleShape),
                )
            },
        )
    }
}

private fun ratio(value: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val sec = (ms / 1000).toInt()
    val m = sec / 60
    val s = sec % 60
    return if (m >= 60) {
        val h = m / 60
        val mm = m % 60
        "%d:%02d:%02d".format(h, mm, s)
    } else {
        "%d:%02d".format(m, s)
    }
}
