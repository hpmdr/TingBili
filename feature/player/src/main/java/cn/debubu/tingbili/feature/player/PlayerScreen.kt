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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
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
    // 顶栏=合集名（“在听哪本书”）；章节行在封面下方，显示 “P1 · 章节名”
    val headerTitle = playerHeaderTitle(s.track)

    var lyricFullscreen by rememberSaveable { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

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
            onMoreClick = { showSettingsSheet = true },
            onBack = onBack,
        )

        Spacer(Modifier.height(12.dp))

        if (!lyricFullscreen) {
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
        } else {
            FullscreenLyricsHeader(onBackToCover = { lyricFullscreen = false })
        }

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

        if (!lyricFullscreen) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                MiniLyricCard(
                    lyrics = s.lyrics,
                    currentIndex = s.currentLyricIndex,
                    onOpenFullscreen = { lyricFullscreen = true },
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                FullscreenLyricsList(
                    state = s,
                    onSeekLyric = vm::seekToLyric,
                    isLoading = s.isLoading,
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

        Spacer(Modifier.height(4.dp))

        // 底部辅助栏：定时 / 字幕切换 / 章节目录 / 倍速
        PlayerAuxBar(
            sleepLabel = sleepLabel(s.sleepRemainSec, s.sleepEndOfChapter),
            sleepActive = s.sleepRemainSec != null || s.sleepEndOfChapter,
            showSubtitleEntry = s.subtitleTracks.size > 1,
            speedLabel = if (s.speed == 1f) "倍速" else "${s.speed}x",
            onTimerClick = { showTimerSheet = true },
            onSubtitleClick = { showSubtitleSheet = true },
            onChapterClick = { showChapterSheet = true },
            onSpeedClick = vm::cycleSpeed,
        )

        Spacer(Modifier.height(8.dp))
    }

    if (showTimerSheet) {
        SleepTimerSheet(
            remainSec = s.sleepRemainSec,
            endOfChapter = s.sleepEndOfChapter,
            onSelectMinutes = { vm.startSleepTimer(it * 60) },
            onSelectChapter = { vm.startEndOfChapterTimer() },
            onCancel = { vm.cancelSleepTimer() },
            onDismiss = { showTimerSheet = false },
        )
    }
    if (showChapterSheet) {
        ChapterSheet(
            queue = s.queue,
            currentIndex = s.currentIndex,
            onSelect = {
                vm.playAt(it)
                showChapterSheet = false
            },
            onDismiss = { showChapterSheet = false },
        )
    }
    if (showSubtitleSheet) {
        SubtitleSheet(
            tracks = s.subtitleTracks,
            selectedIndex = s.subtitleIndex,
            onSelect = {
                vm.selectSubtitle(it)
                showSubtitleSheet = false
            },
            onDismiss = { showSubtitleSheet = false },
        )
    }
    if (showSettingsSheet) {
        PlaybackSettingsSheet(onDismiss = { showSettingsSheet = false })
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
    onMoreClick: () -> Unit,
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

        IconButton(
            onClick = onMoreClick,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "播放设置",
            )
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

/** 睡眠定时辅助栏文案：倒计时 mm:ss / 播完本章 / 定时 */
private fun sleepLabel(remainSec: Int?, endOfChapter: Boolean): String = when {
    endOfChapter -> "本章止"
    remainSec != null -> String.format("%d:%02d", remainSec / 60, remainSec % 60)
    else -> "定时"
}

@Composable
private fun PlayerAuxBar(
    sleepLabel: String,
    sleepActive: Boolean,
    showSubtitleEntry: Boolean,
    speedLabel: String,
    onTimerClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onChapterClick: () -> Unit,
    onSpeedClick: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuxItem(
            icon = Icons.Filled.Timer,
            label = sleepLabel,
            tint = if (sleepActive) activeColor else idleColor,
            contentDescription = "睡眠定时",
            onClick = onTimerClick,
        )
        if (showSubtitleEntry) {
            AuxItem(
                icon = Icons.Filled.ClosedCaption,
                label = "字幕",
                tint = idleColor,
                contentDescription = "切换字幕",
                onClick = onSubtitleClick,
            )
        }
        AuxItem(
            icon = Icons.Filled.List,
            label = "章节",
            tint = idleColor,
            contentDescription = "章节目录",
            onClick = onChapterClick,
        )
        AuxItem(
            icon = Icons.Filled.Speed,
            label = speedLabel,
            tint = idleColor,
            contentDescription = "倍速",
            onClick = onSpeedClick,
        )
    }
}

@Composable
private fun AuxItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** 封面态迷你实时字幕：上一行 + 当前行 + 下两行，点击进全屏字幕 */
@Composable
private fun MiniLyricCard(
    lyrics: List<cn.debubu.tingbili.data.bilibili.model.LyricLine>,
    currentIndex: Int,
    onOpenFullscreen: () -> Unit,
) {
    val prev = lyrics.getOrNull(currentIndex - 1)?.text
    val current = lyrics.getOrNull(currentIndex)?.text
    val next1 = lyrics.getOrNull(currentIndex + 1)?.text
    val next2 = lyrics.getOrNull(currentIndex + 2)?.text
    Surface(
        onClick = onOpenFullscreen,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ClosedCaption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "实时字幕",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "全屏字幕 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current == null) {
                Text(
                    text = "暂无字幕",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (!prev.isNullOrBlank()) {
                    Text(
                        text = prev,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = current,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!next1.isNullOrBlank()) {
                    Text(
                        text = next1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!next2.isNullOrBlank()) {
                    Text(
                        text = next2,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenLyricsHeader(onBackToCover: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "全屏字幕",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onBackToCover) { Text("切回封面") }
    }
    Spacer(Modifier.height(8.dp))
}

/** 全屏大字字幕：大字号 + 当前行高亮卡 + 自动跟随 + 点行跳转，末尾带 AI 标注 */
@Composable
private fun FullscreenLyricsList(
    state: PlayerUiState,
    onSeekLyric: (cn.debubu.tingbili.data.bilibili.model.LyricLine) -> Unit,
    isLoading: Boolean,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.currentLyricIndex) {
        if (state.currentLyricIndex >= 0) {
            listState.animateScrollToItem(state.currentLyricIndex)
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(
            items = state.lyrics,
            key = { _, line -> line.timeMs },
        ) { idx, line ->
            val isCurrent = idx == state.currentLyricIndex
            if (isCurrent) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { onSeekLyric(line) },
                ) {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { onSeekLyric(line) }
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
        if (state.lyrics.isNotEmpty() && state.subtitleLabel != null) {
            item(key = "subtitle-label") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.subtitleIsAi) {
                            "字幕由 AI 生成，可能有误 · ${state.subtitleLabel}"
                        } else {
                            "字幕 · ${state.subtitleLabel}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    remainSec: Int?,
    endOfChapter: Boolean,
    onSelectMinutes: (Int) -> Unit,
    onSelectChapter: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "睡眠定时", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (remainSec != null || endOfChapter) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (endOfChapter) "播完本章后自动停止" else "将在 ${sleepLabel(remainSec, false)} 后自动停止",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        TextButton(onClick = { onCancel(); onDismiss() }) { Text("取消定时") }
                    }
                }
            }
            SleepOptionRow(label = "播完本章", selected = endOfChapter, onClick = { onSelectChapter(); onDismiss() })
            SleepOptionRow(label = "15 分钟", selected = false, onClick = { onSelectMinutes(15); onDismiss() })
            SleepOptionRow(label = "30 分钟", selected = false, onClick = { onSelectMinutes(30); onDismiss() })
            SleepOptionRow(label = "60 分钟", selected = false, onClick = { onSelectMinutes(60); onDismiss() })
        }
    }
}

@Composable
private fun SleepOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (selected) {
                Text(text = "✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    queue: List<cn.debubu.tingbili.core.data.model.Track>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "章节目录（${queue.size}）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(bottom = 32.dp),
        ) {
            itemsIndexed(items = queue) { idx, track ->
                val selected = idx == currentIndex
                ListItem(
                    headlineContent = {
                        Text(
                            text = chapterLabel(track.title, track.pageIndex, track.pageCount),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    supportingContent = { Text(text = track.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Text(
                            text = formatChapterDuration(track.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { onSelect(idx) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleSheet(
    tracks: List<cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "切换字幕", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            tracks.forEachIndexed { idx, track ->
                val selected = idx == selectedIndex
                Surface(
                    onClick = { onSelect(idx) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = track.displayLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (selected) {
                            Text(text = "✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "播放设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "设置内容整理中，敬请期待",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatChapterDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSec = durationMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
