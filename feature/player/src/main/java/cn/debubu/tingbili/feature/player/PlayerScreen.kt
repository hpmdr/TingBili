package cn.debubu.tingbili.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    onLoginClick: () -> Unit = {},
    onDetailClick: (String) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    vm: PlayerViewModel = hiltViewModel()
) {
    val imageFormat = LocalImageFormat.current
    val context = LocalContext.current
    val trackUi by vm.trackUi.collectAsStateWithLifecycle()
    val progUi by vm.progressUi.collectAsStateWithLifecycle()
    val lyrUi by vm.lyricUi.collectAsStateWithLifecycle()
    val pipelineInfo by vm.pipelineInfo.collectAsStateWithLifecycle()
    val qualityLabel by vm.audioQualityLabel.collectAsStateWithLifecycle()
    val qualityLine by vm.qualityLine.collectAsStateWithLifecycle()
    val audioOptions by vm.audioOptions.collectAsStateWithLifecycle()
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val refreshMessage by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(refreshMessage) {
        refreshMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }
    // 顶栏=合集名（“在听哪本书”）；章节行在封面下方，显示 “P1 · 章节名”
    val headerTitle = playerHeaderTitle(trackUi.track)

    var lyricFullscreen by rememberSaveable { mutableStateOf(false) }
    // 全屏字幕时拦截返回：先退回封面态，再按一次才退出播放页
    BackHandler(enabled = lyricFullscreen) { lyricFullscreen = false }
    var showTimerSheet by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var infoReturnToMore by remember { mutableStateOf(false) }
    var showQualitySheet by remember { mutableStateOf(false) }

    val coverModel = trackUi.track?.cover
        ?.takeIf { it.isNotBlank() }
        ?.biliImage(BiliImageVariant.LargeSquare, imageFormat)
    Box(modifier = Modifier.fillMaxSize()) {
        // 沉浸背景：同一张封面做模糊底，主题罩子保证深浅主题都压得住字
        AsyncImage(
            model = coverModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(28.dp)
                .alpha(0.35f),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )
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
            onMoreClick = { showMoreSheet = true },
            onBack = onBack,
        )

        Spacer(Modifier.height(12.dp))

        if (!lyricFullscreen) {
            AsyncImage(
                model = coverModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .shadow(18.dp, RoundedCornerShape(16.dp), clip = false)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(16.dp),
                    ),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = playerChapterLabel(trackUi.track),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = trackUi.track?.author?.takeIf { it.isNotBlank() }?.let { "UP主 · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        trackUi.errorMessage?.let { errorMsg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        if (trackUi.isLoading) {
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
                    lyrics = lyrUi.lyrics,
                    currentIndex = lyrUi.currentLyricIndex,
                    status = lyrUi.subtitleLoadState,
                    fontScale = lyrUi.subtitleScale,
                    onCardClick = {
                        when (lyrUi.subtitleLoadState) {
                            SubtitleLoadState.Ready -> lyricFullscreen = true
                            SubtitleLoadState.Empty -> android.widget.Toast.makeText(
                                context, "暂无字幕", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            SubtitleLoadState.NoAuth -> onLoginClick()
                            SubtitleLoadState.Error -> vm.retrySubtitles()
                            SubtitleLoadState.Loading -> Unit
                        }
                    },
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                FullscreenLyricsList(
                    state = lyrUi,
                    onSeekLyric = vm::seekToLyric,
                    isLoading = trackUi.isLoading,
                )
            }
        }

        PlaybackProgressBar(
            positionMs = progUi.positionMs,
            bufferedPositionMs = progUi.bufferedPositionMs,
            durationMs = progUi.durationMs,
            enabled = !trackUi.isLoading,
            onSeek = vm::seekTo,
        )

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.previous() }, enabled = !trackUi.isLoading) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.size(30.dp),
                )
            }

            IconButton(onClick = { vm.skipBackward() }, enabled = !trackUi.isLoading) {
                SkipIcon(
                    seconds = trackUi.stepSec,
                    forward = false,
                    contentDescription = "后退${trackUi.stepSec}秒",
                )
            }

            if (trackUi.isLoading) {
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(12.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                ),
                            ),
                        )
                        .clickable(onClick = { vm.toggle() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (trackUi.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (trackUi.isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            IconButton(onClick = { vm.skipForward() }, enabled = !trackUi.isLoading) {
                SkipIcon(
                    seconds = trackUi.stepSec,
                    forward = true,
                    contentDescription = "前进${trackUi.stepSec}秒",
                )
            }

            IconButton(onClick = { vm.next() }, enabled = !trackUi.isLoading) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 底部辅助栏：定时 / 字幕切换 / 章节目录 / 音质 / 倍速
        PlayerAuxBar(
            sleepLabel = sleepLabel(progUi.sleepRemainSec, progUi.sleepEndOfChapter),
            sleepActive = progUi.sleepRemainSec != null || progUi.sleepEndOfChapter,
            showSubtitleEntry = lyrUi.subtitleTracks.size > 1,
            speedLabel = if (trackUi.speed == 1f) "倍速" else "${trackUi.speed}x",
            qualityLabel = qualityLabel,
            onTimerClick = { showTimerSheet = true },
            onSubtitleClick = { showSubtitleSheet = true },
            onChapterClick = { showChapterSheet = true },
            onQualityClick = { vm.loadAudioOptions(); showQualitySheet = true },
            onSpeedClick = vm::cycleSpeed,
            onInfoClick = { infoReturnToMore = false; showInfoSheet = true },
        )

        Spacer(Modifier.height(8.dp))
    }
    }

    if (showTimerSheet) {
        SleepTimerSheet(
            remainSec = progUi.sleepRemainSec,
            totalMin = progUi.sleepTotalSec?.div(60),
            finishChapter = progUi.sleepFinishChapter,
            finishDefault = vm.sleepFinishDefault.collectAsStateWithLifecycle().value,
            lastMin = vm.sleepLastMin.collectAsStateWithLifecycle().value,
            endOfChapter = progUi.sleepEndOfChapter,
            onSelectMinutes = { min, finish -> vm.startSleepTimer(min * 60, finish) },
            onToggleFinish = { vm.setSleepFinishChapter(it) },
            onCancel = { vm.cancelSleepTimer() },
            onDismiss = { showTimerSheet = false },
        )
    }
    if (showChapterSheet) {
        ChapterSheet(
            queue = trackUi.queue,
            currentIndex = trackUi.currentIndex,
            onSelect = {
                vm.playAt(it)
                showChapterSheet = false
            },
            onDismiss = { showChapterSheet = false },
        )
    }
    if (showSubtitleSheet) {
        SubtitleSheet(
            tracks = lyrUi.subtitleTracks,
            selectedIndex = lyrUi.subtitleIndex,
            onSelect = {
                vm.selectSubtitle(it)
                showSubtitleSheet = false
            },
            onDismiss = { showSubtitleSheet = false },
        )
    }
    if (showMoreSheet) {
        PlayerMoreSheet(
            refreshing = isRefreshing,
            onRefresh = { vm.refreshBv() },
            onInfo = {
                infoReturnToMore = true
                showMoreSheet = false
                showInfoSheet = true
            },
            onDetail = {
                trackUi.track?.bvid?.takeIf { it.isNotBlank() }?.let(onDetailClick)
                showMoreSheet = false
            },
            onSettings = {
                showMoreSheet = false
                onSettingsClick()
            },
            onDismiss = { showMoreSheet = false },
        )
    }
    if (showInfoSheet) {
        PlaybackInfoSheet(
            info = pipelineInfo,
            qualityLine = qualityLine,
            hasTrack = trackUi.track != null,
            isActive = trackUi.isPlaying || trackUi.isLoading,
            showBack = infoReturnToMore,
            onBack = {
                showInfoSheet = false
                showMoreSheet = true
            },
            onDismiss = { showInfoSheet = false },
        )
    }
    if (showQualitySheet) {
        AudioQualitySheet(
            options = audioOptions,
            onRetry = { vm.loadAudioOptions() },
            onSelect = { code ->
                if (!vm.selectAudioQuality(code)) {
                    showQualitySheet = false
                    onLoginClick()
                }
            },
            onDismiss = { showQualitySheet = false },
            showLoginHint = !loggedIn,
            onLoginClick = { showQualitySheet = false; onLoginClick() },
        )
    }
}

/** 右上 ⋮ 动作表（A4 极简）：无图标细分割线，内容 / 播放两组；与底部 AuxBar 零重复。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMoreSheet(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onInfo: () -> Unit,
    onDetail: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "更多操作",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            MoreGroupTitle("内容")
            MoreBareRow(
                title = if (refreshing) "正在刷新…" else "刷新当前内容",
                subtitle = "重新拉取分P与播放地址",
                busy = refreshing,
                enabled = !refreshing,
                onClick = onRefresh,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
            MoreBareRow(
                title = "查看 BV 详情",
                subtitle = "跳转视频详情页",
                onClick = onDetail,
            )
            MoreGroupTitle("播放")
            MoreBareRow(
                title = "播放信息",
                subtitle = "只看：解码 · 声道 · 倍速 · DSP 状态",
                onClick = onInfo,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
            MoreBareRow(
                title = "播放设置",
                subtitle = "可改：步进秒数 · 定时默认 · 字幕字号",
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun MoreGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** A4 极简行：无图标，左标题 + 副标题，右 › / 转圈，行间细分割线。 */
@Composable
private fun MoreBareRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(
                text = "›",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
/**
 * 播放信息二级页（A4 极简，随更多菜单同语言）：‹ 返回上一级，音频 / 播放分组 K-V + 小知识。
 * 只读；未在播（无节目/暂停态未起播）时技术行整组隐藏，只留开关与偏好行 + 一句引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackInfoSheet(
    info: cn.debubu.tingbili.core.media.PipelineInfo,
    qualityLine: String,
    hasTrack: Boolean,
    isActive: Boolean,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (showBack) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回更多操作",
                        )
                    }
                }
                Text(
                    text = "播放信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = if (showBack) 56.dp else 0.dp),
                )
            }
            if (!hasTrack || !isActive) {
                PlaybackInfoRow("当前状态", if (!hasTrack) "暂无在播" else "尚未播放")
                Text(
                    text = "开始播放后，这里会显示解码器、格式、码率等真实管线参数；下面是当前开关与偏好，随时可改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(4.dp))
                PlaybackInfoRow(
                    "DSP 卸载",
                    if (!info.offloadEnabled) "已关闭（可在设置里打开，灭屏听歌更省电）"
                    else "已开启，播放时条件满足会自动接管"
                )
                PlaybackInfoRow("音质", qualityLine)
                PlaybackInfoRow("倍速", "${info.speed}x")
            } else {
            // 播放中：管线参数尚未就绪时显示"读取中…"（如切集/缓冲瞬间），不写"未知"吓人。
            MoreGroupTitle("音频")
            val initializing = info.decoderName == null && !info.offloadSleeping
            PlaybackInfoRow("封装/编码", info.mimeType ?: "读取中…")
            val codecs = info.codecs
            if (codecs != null) PlaybackInfoRow("编码参数", codecs)
            PlaybackInfoRow("码率", if (info.bitrateKbps != null) "${info.bitrateKbps} kbps" else "读取中…")
            PlaybackInfoRow("音质", qualityLine)
            PlaybackInfoRow("采样率", if (info.sampleRateHz > 0) "${info.sampleRateHz} Hz" else "读取中…")
            PlaybackInfoRow("声道", if (info.channelCount > 0) info.channelCount.toString() else "读取中…")
            MoreGroupTitle("播放")
            PlaybackInfoRow("解码器", info.decoderName ?: if (info.offloadSleeping) "DSP 芯片直接解码（不经过 CPU）" else "读取中…")
            PlaybackInfoRow("解码方式", if (info.offloadSleeping) "DSP 硬件直解（功耗最低）" else if (initializing) "读取中…" else info.decoderKind)
            PlaybackInfoRow("DSP 卸载", if (!info.offloadEnabled) "已关闭（可在设置里打开，灭屏听歌更省电）" else if (info.offloadSleeping) "生效中：低功耗芯片正在解码，主芯片可休眠" else "已开启，当前不支持，已自动回落")
            PlaybackInfoRow("倍速", "${info.speed}x")
            if (info.offloadEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(text = "小知识：DSP 卸载就是把解码交给低功耗芯片、主芯片休眠来省电；条件不满足时自动回落，不断播。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                if (info.offloadSleeping) {
                    Text(text = "DSP 省电生效中不支持倍速，已保持 1.0x；退出接管后可变速。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                } else {
                    Text(text = "当前已自动回落到 CPU 解码，倍速可用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            }
        }
    }
}

@Composable
private fun PlaybackInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(96.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioQualitySheet(
    options: PlayerViewModel.AudioOptionsUi,
    onRetry: () -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    showLoginHint: Boolean,
    onLoginClick: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "切换音质", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "只列出本视频实际提供的档位（从高到低）；换档不断播",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (options) {
                is PlayerViewModel.AudioOptionsUi.Hidden,
                is PlayerViewModel.AudioOptionsUi.Loading -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                is PlayerViewModel.AudioOptionsUi.Error -> {
                    Text(text = options.msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry) { Text("重试") }
                }
                is PlayerViewModel.AudioOptionsUi.Ready -> {
                    options.options.forEach { item ->
                        Surface(
                            onClick = { onSelect(item.code) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (item.selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (item.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                    Text(
                                        text = item.subTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (item.selected) {
                                    Text(text = "✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    if (showLoginHint) {
                        Surface(
                            onClick = onLoginClick,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "登录解锁更高音质",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "192K / 杜比 / Hi-Res 需登录（部分需大会员）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(text = "去登录 ›", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatMs(shownPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatMs(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                    // 细线风：与 mini 播放器顶部的 2.dp 进度线统一，无常驻圆点
                    val centerY = size.height / 2f
                    val baseHeight = 3.dp.toPx()
                    val activeHeight = 3.dp.toPx()

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
            thumb = {
                // 无常驻圆点：固定占位防轨道跳动，手指拖动时才画小圆点当手柄
                Box(
                    modifier = Modifier.size(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (dragRatio != null) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(activeColor, CircleShape),
                        )
                    }
                }
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
    qualityLabel: String,
    onTimerClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onChapterClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(vertical = 4.dp),
        )
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
                tint = if (speedLabel != "倍速") activeColor else idleColor,
                contentDescription = "倍速",
                onClick = onSpeedClick,
            )
            AuxItem(
                icon = Icons.Filled.HighQuality,
                label = qualityLabel,
                tint = idleColor,
                contentDescription = "音质",
                onClick = onQualityClick,
            )
            AuxItem(
                icon = Icons.Filled.Info,
                label = "信息",
                tint = idleColor,
                contentDescription = "播放信息",
                onClick = onInfoClick,
            )
        }
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

/** 封面态迷你实时字幕：上一行 + 当前行 + 下两行。点击行为按字幕状态分流 */
@Composable
private fun MiniLyricCard(
    lyrics: List<cn.debubu.tingbili.data.bilibili.model.LyricLine>,
    currentIndex: Int,
    status: SubtitleLoadState,
    onCardClick: () -> Unit,
    fontScale: Float = 1f,
) {
    val prev = lyrics.getOrNull(currentIndex - 1)?.text
    val current = lyrics.getOrNull(currentIndex)?.text
    val next1 = lyrics.getOrNull(currentIndex + 1)?.text
    val next2 = lyrics.getOrNull(currentIndex + 2)?.text
    Surface(
        onClick = onCardClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
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
                    text = "实时 AI 字幕",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                // 只有真有字幕才给全屏入口：没字幕点进去也是空的，直接在外层提示
                Text(
                    text = when (status) {
                        SubtitleLoadState.Ready -> "全屏字幕 ›"
                        SubtitleLoadState.NoAuth -> "去登录 ›"
                        SubtitleLoadState.Error -> "重试"
                        SubtitleLoadState.Loading -> "加载中…"
                        SubtitleLoadState.Empty -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (status) {
                SubtitleLoadState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "字幕加载中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SubtitleLoadState.NoAuth -> {
                    Text(
                        text = "登录后查看字幕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                SubtitleLoadState.Error -> {
                    Text(
                        text = "字幕加载失败，点击重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SubtitleLoadState.Empty -> {
                    Text(
                        text = "暂无字幕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SubtitleLoadState.Ready -> if (current == null) {
                    Text(
                        text = "暂无字幕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 66.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                if (!prev.isNullOrBlank()) {
                    Text(
                        text = prev,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = current,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!next1.isNullOrBlank()) {
                    Text(
                        text = next1,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!next2.isNullOrBlank()) {
                    Text(
                        text = next2,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                }
                }
            }
        }
    }
}

/** 全屏字幕：当前行主题色大字 + 上下文小字灰，当前行居中跟随 + 点行跳转，末尾带 AI 标注 */
@Composable
private fun FullscreenLyricsList(
    state: PlayerViewModel.LyricUiState,
    onSeekLyric: (cn.debubu.tingbili.data.bilibili.model.LyricLine) -> Unit,
    isLoading: Boolean,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        // 上下各留半屏内边距，首尾行也能居中
        val centerPadding = maxHeight / 2
        // 闭环居中：先粗定位把目标行弄进布局，再按实测位置 scrollBy 精调到正中。
        // 不依赖 scrollToItem 的 offset 语义（真机证明其负偏移在冷/热布局下都落不到中央，
        // 只会把目标行晾在底边），scrollBy 是纯相对位移无歧义；到首尾时自然钳制。
        // 尺寸变化也要重滚：进场转场时首帧约束可能是中间态只滚一次会定错位置
        LaunchedEffect(state.currentLyricIndex, state.lyrics.size, centerPadding) {
            val idx = state.currentLyricIndex
            if (idx < 0 || idx >= state.lyrics.size) return@LaunchedEffect
            // 瞬时跟随不用动画：动画会被下一句打断，越跟越偏
            try {
                listState.scrollToItem(idx)
                // 等目标行真实出现在布局里（冷进场时首滚只为把它组合出来）
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                }.filterNotNull().first()
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == idx }
                    ?: return@LaunchedEffect
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val delta = (item.offset + item.size / 2 - viewportCenter).toFloat()
                if (delta != 0f) listState.scroll { scrollBy(delta) }
            } catch (_: Exception) {
                // 切歌/切字幕轨时列表并发变化，忽略本次跟随
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = centerPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(
                items = state.lyrics,
                // timeMs 会重（AI 字幕同秒多行），key 必须唯一否则复用错位连带滚错位置
                key = { idx, line -> "$idx-${line.timeMs}" },
            ) { idx, line ->
                val isCurrent = idx == state.currentLyricIndex
                Text(
                    text = line.text,
                    style = if (isCurrent) {
                        MaterialTheme.typography.titleMedium.copy(
                            fontSize = MaterialTheme.typography.titleMedium.fontSize * state.subtitleScale,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * state.subtitleScale,
                        )
                    },
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) { onSeekLyric(line) }
                        .padding(horizontal = 16.dp, vertical = if (isCurrent) 6.dp else 2.dp),
                )
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SleepTimerSheet(
    remainSec: Int?,
    totalMin: Int?,
    finishChapter: Boolean,
    finishDefault: Boolean,
    lastMin: Int,
    endOfChapter: Boolean,
    onSelectMinutes: (Int, Boolean) -> Unit,
    onToggleFinish: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = remainSec != null || endOfChapter
    // 滚轮初值：运行中沿用本次设定，否则上次所选、无记忆则 60（60 居中露出 30/90，暗示可滑）
    // （不在预设里也没关系，滚轮全覆盖，都贴到 30 分钟格）
    // 带 key：冷启动时 flow 初值尚未落盘重组后要用最新值重算（无 key 会钉死初值 60/false）
    var wheelMin by remember(totalMin, lastMin) {
        mutableIntStateOf(snapSleepMin(totalMin ?: lastMin))
    }
    var finish by remember(remainSec != null, endOfChapter, finishDefault) {
        mutableStateOf(if (remainSec != null) finishChapter else finishDefault)
    }
    // 内容高于半屏（运行中多一行状态），直接全展开，免得确定按钮被半屏卡掉
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "睡眠定时", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (running) {
                // 运行中提示只用字体颜色强调，不铺底色：图标 + 主色正文 + 文本按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (endOfChapter) "播完本章后自动停止"
                        else "将在 ${sleepLabel(remainSec, false)} 后自动停止",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onCancel(); onDismiss() }) { Text("取消定时") }
                }
            }
            if (!endOfChapter) {
                // 分钟滚轮：30 分钟一格，上限 360
                MinuteWheel(value = wheelMin, onValueChange = { wheelMin = it })
                // 到点后播完本章再停：倒计时结束转入播完即停，不立即暂停
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            finish = !finish
                            if (remainSec != null) onToggleFinish(finish)
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "到点后播完本章再停", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "定时结束时播完当前章节再暂停",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = finish, onCheckedChange = {
                        finish = it
                        if (remainSec != null) onToggleFinish(it)
                    })
                }
                Button(
                    onClick = { onSelectMinutes(wheelMin, finish); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (running) "更新定时（$wheelMin 分钟）" else "开始定时（$wheelMin 分钟）")
                }
            }
        }
    }
}

/** 上限 600、30 分钟一格：30/60/…/600 共 20 格 */
private const val SLEEP_MAX_MIN = 600
private const val SLEEP_STEP_MIN = 30
private const val SLEEP_GRID_COUNT = SLEEP_MAX_MIN / SLEEP_STEP_MIN

/** 就近贴到 30 分钟格 */
private fun snapSleepMin(min: Int): Int =
    ((min.coerceIn(1, SLEEP_MAX_MIN) + SLEEP_STEP_MIN / 2) / SLEEP_STEP_MIN)
        .coerceIn(1, SLEEP_GRID_COUNT) * SLEEP_STEP_MIN

/** 分钟滚轮：30 分钟一格，行内纯数字选中只变颜色；“分钟”钉死框外不跟滚 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MinuteWheel(value: Int, onValueChange: (Int) -> Unit) {
    val itemH = 48.dp
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (value / SLEEP_STEP_MIN - 1).coerceIn(0, SLEEP_GRID_COUNT - 1),
    )
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    // 视口中心项：滚动中每帧都变，只做计算不做高亮避免闪烁
    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) null
            else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull {
                    kotlin.math.abs((it.offset + it.size / 2) - center)
                }?.index
            }
        }
    }
    // 停稳后的选择下标：滚动中冻结在上一次的值高亮不动只有停稳才结算一次
    var settled by remember(value) {
        mutableIntStateOf((value / SLEEP_STEP_MIN - 1).coerceIn(0, SLEEP_GRID_COUNT - 1))
    }
    // 外部值变化（冷启动落盘/记忆恢复）滚到对应格；自己结算时与列表一致为空操作
    LaunchedEffect(value) {
        listState.scrollToItem((value / SLEEP_STEP_MIN - 1).coerceIn(0, SLEEP_GRID_COUNT - 1))
    }
    LaunchedEffect(centerIndex, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            centerIndex?.let {
                settled = it
                val m = (it + 1) * SLEEP_STEP_MIN
                if (m != value) onValueChange(m)
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            modifier = Modifier
                .fillMaxWidth()
                .height(itemH * 3),
            contentPadding = PaddingValues(vertical = itemH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(SLEEP_GRID_COUNT, key = { it }) { i ->
                val selected = i == settled
                Text(
                    text = "${(i + 1) * SLEEP_STEP_MIN}",
                    modifier = Modifier.fillMaxWidth().height(itemH)
                        .wrapContentHeight(Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 中线刻度：只画上下两条细线不给内容铺背景
        Column(modifier = Modifier.fillMaxWidth().height(itemH * 3)) {
            Spacer(modifier = Modifier.height(itemH))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(itemH))
        }
        // 单位钉死在中间行右侧：彻底移出滚动列表怎么划都不动（对标 iOS 倒计时滚轮）
        Text(
            text = "分钟",
            modifier = Modifier.align(Alignment.Center).offset(x = 34.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
            itemsIndexed(items = queue, key = { _, track -> "${track.bvid}:${track.cid}" }) { idx, track ->
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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

private fun formatChapterDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSec = durationMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
