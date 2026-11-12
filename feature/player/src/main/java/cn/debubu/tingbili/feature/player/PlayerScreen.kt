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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.component.FavoriteFolderOption
import cn.debubu.tingbili.core.ui.component.FavoriteFolderSinglePickerSheet
import cn.debubu.tingbili.core.ui.component.TingBiliLyricList
import cn.debubu.tingbili.core.ui.component.LyricTone
import cn.debubu.tingbili.core.ui.component.TingBiliMessageBus
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.TingBiliBottomSheet
import cn.debubu.tingbili.core.ui.formatDurationMs
import cn.debubu.tingbili.core.ui.trackMetaLabel
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@Composable
fun PlayerScreen(
    onBack: () -> Unit = {},
    onLoginClick: () -> Unit = {},
    onDetailClick: (String) -> Unit = {},
    onUpClick: (Long) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    vm: PlayerViewModel = hiltViewModel()
) {
    val imageFormat = LocalImageFormat.current
    val trackUi by vm.trackUi.collectAsStateWithLifecycle()
    // 注意：进度流（1Hz）不在这里订阅——它只被「进度条 / 辅助栏 / 定时弹层」用，
    // 顶层订阅会让整个播放页 body 每秒重跑一次（REVIEW_REPORT B-03）。
    // 各子组件自己收（见 ProgressBarSection / PlayerAuxBarSection / SleepTimerSheetSection）。
    val lyrUi by vm.lyricUi.collectAsStateWithLifecycle()
    val pipelineInfo by vm.pipelineInfo.collectAsStateWithLifecycle()
    val qualityLabel by vm.audioQualityLabel.collectAsStateWithLifecycle()
    val audioOptions by vm.audioOptions.collectAsStateWithLifecycle()
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val isDefaultFavorite by vm.isDefaultFavorite.collectAsStateWithLifecycle()
    val favoriteFolders by vm.favoriteFolders.collectAsStateWithLifecycle()
    // 一次性提示走全局总线：发消息即可，显示位置/队列由壳层唯一宿主负责
    val refreshMessage by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(refreshMessage) {
        refreshMessage?.let {
            vm.consumeMessage()
            TingBiliMessageBus.show(it)
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
    var showFavoriteSheet by remember { mutableStateOf(false) }

    // 歌词行跳转的撤销提示：误触跳走时 4s 内可一键回到原位置
    var undoJump by remember { mutableStateOf<JumpUndo?>(null) }
    val undoScope = rememberCoroutineScope()
    LaunchedEffect(lyricFullscreen) {
        if (!lyricFullscreen) undoJump = null
    }

    val coverModel = trackUi.track?.cover
        ?.takeIf { it.isNotBlank() }
        ?.biliImage(BiliImageVariant.LargeCover, imageFormat)
    // 纯色背景：曾试过“全屏模糊封面 + 渐变罩子”，罩子压完几乎看不见，还多一层全屏 RenderEffect，已移除
    // 提示走全局总线（TingBiliMessageBus）：整个 App 只有一个 Snackbar 宿主，挂在壳层顶栏下方。
    TingBiliScaffold { innerPadding ->
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(innerPadding)
    ) {
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
                    .aspectRatio(16f / 9f)
                    .shadow(18.dp, RoundedCornerShape(16.dp), clip = false)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(16.dp),
                    ),
            )

            Spacer(Modifier.height(12.dp))

            // 章节标题行：左标题列吃剩余宽度（weight=1f，最多两行省略），
            // 右侧爱心固定 48dp 再长也挤不动；点标题进章节目录看全文。
            PlayerChapterHeader(
                chapterLabel = playerChapterLabel(trackUi.track, trackUi.currentIndex),
                author = trackUi.track?.author,
                favoriteAvailable = trackUi.track != null,
                isFavorite = isDefaultFavorite,
                onChapterClick = { showChapterSheet = true },
                onAuthorClick = { vm.openUpSpace(onUpClick) },
                onFavoriteClick = { vm.toggleDefaultFavorite() },
                onFavoriteLongClick = { showFavoriteSheet = true },
            )
        }

        // 音源错误不再放字幕卡上方（张冠李戴）：阻塞式失败走下面的 PlaybackErrorDialog，
        // 非阻塞断网走顶部全局横幅。字幕卡只显字幕自己的状态。
        Spacer(Modifier.height(12.dp))

        if (!lyricFullscreen) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                MiniLyricCard(
                    lyrics = lyrUi.lyrics,
                    currentIndex = lyrUi.currentLyricIndex,
                    status = lyrUi.subtitleLoadState,
                    label = lyrUi.subtitleLabel,
                    trackCount = lyrUi.subtitleTracks.size,
                    onSwitchTrack = { showSubtitleSheet = true },
                    fontScale = lyrUi.subtitleScale,
                    modifier = Modifier.fillMaxSize(),
                    onCardClick = {
                        when (lyrUi.subtitleLoadState) {
                            SubtitleLoadState.Ready -> lyricFullscreen = true
                            SubtitleLoadState.Empty -> TingBiliMessageBus.show("暂无字幕")
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
                Box(modifier = Modifier.fillMaxSize()) {
                    FullscreenLyricsList(
                        state = lyrUi,
                        onSeekLyric = { line ->
                            // 读瞬时值而不是组合态：不建立 1Hz 订阅，点击时也是最新位置
                            val from = vm.currentPositionMs()
                            vm.seekToLyric(line)
                            val token = (undoJump?.token ?: 0L) + 1L
                            undoJump = JumpUndo(from, formatMs(line.timeMs), token)
                            undoScope.launch {
                                delay(UNDO_HINT_MS)
                                if (undoJump?.token == token) undoJump = null
                            }
                        },
                        onExit = { lyricFullscreen = false },
                        isLoading = trackUi.isLoading,
                    )
                    undoJump?.let { undo ->
                        UndoJumpHint(
                            label = undo.toLabel,
                            onUndo = {
                                vm.seekTo(undo.fromMs)
                                undoJump = null
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }

        // 进度与字幕卡间距取 10dp：时间已移至线下，上方是 32dp 触摸盒顶部 27dp 透明，
        // 18dp 会显空，收至 10dp 后卡底到视觉线约 37dp，与下方视觉相当；下方按钮区仍 8dp。
        Spacer(Modifier.height(10.dp))

        ProgressBarSection(vm = vm, enabled = !trackUi.isLoading)

        Spacer(Modifier.height(8.dp))

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

        Spacer(Modifier.height(6.dp))

        // 底部辅助栏固定 5 项：定时 / 章节 / 倍速 / 音质 / 信息（字幕切换在字幕卡头部，避免条目增减导致整排跳动）
        PlayerAuxBarSection(
            vm = vm,
            speedLabel = if (trackUi.speed == 1f) "倍速" else "${trackUi.speed}x",
            qualityLabel = qualityLabel,
            onTimerClick = { showTimerSheet = true },
            onChapterClick = { showChapterSheet = true },
            onQualityClick = { vm.loadAudioOptions(); showQualitySheet = true },
            onSpeedClick = vm::cycleSpeed,
            onInfoClick = { infoReturnToMore = false; showInfoSheet = true },
        )

        Spacer(Modifier.height(8.dp))
    }
    }
    }

    if (showFavoriteSheet && trackUi.track != null) {
        FavoriteFolderSinglePickerSheet(
            title = "收藏到收藏夹",
            subtitle = trackUi.track?.title,
            folders = favoriteFolders.map {
                FavoriteFolderOption(it.id, it.name, it.isDefault, it.checked)
            },
            onDismiss = { showFavoriteSheet = false },
            onToggle = { folderId ->
                vm.toggleFavoriteFolder(folderId)
                showFavoriteSheet = false
            },
            onCreateFolder = { name ->
                vm.createFavoriteFolderAndAdd(name)
                showFavoriteSheet = false
            },
        )
    }

    if (showTimerSheet) {
        SleepTimerSheetSection(vm = vm, onDismiss = { showTimerSheet = false })
    }
    if (showChapterSheet) {
        ChapterSheet(
            queue = trackUi.queue,
            currentIndex = trackUi.currentIndex,
            sourceTitle = trackUi.sourceTitle,
            playOrder = trackUi.playOrder,
            shuffleEnabled = trackUi.shuffleEnabled,
            onPlayOrderChange = vm::setPlayOrder,
            onShuffleChange = vm::setShuffleEnabled,
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
    // 阻塞式播放失败弹窗：errorMessage 非空 + 非加载中 +（在线 || 自动重试已用完 || 断网类错误）。
    // 断网类错误即使仍离线也要弹（顶部横幅只讲"网络断开"，不讲起播失败）；
    // 返回键/点外部 = 知道了（只关框不清队列，同一错误不再自动重试打扰）。
    val playbackError = trackUi.errorMessage
    if (trackUi.showErrorDialog && playbackError != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissPlaybackError() },
            title = { Text(if (trackUi.isOfflineError) "网络已断开" else "播放失败") },
            text = {
                Text(
                    if (trackUi.isOfflineError) {
                        "$playbackError\n\n网络恢复后会自动重试，也可以现在点「重试」。"
                    } else {
                        playbackError
                    },
                )
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            vm.next()
                            vm.dismissPlaybackError()
                        },
                        enabled = trackUi.queue.isNotEmpty(),
                    ) { Text("下一首") }
                    TextButton(
                        onClick = { vm.playAt(trackUi.currentIndex) },
                        enabled = trackUi.queue.isNotEmpty() && !trackUi.isLoading,
                    ) { Text("重试") }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPlaybackError() }) { Text("知道了") }
            },
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
                showQualitySheet = false
                if (!vm.selectAudioQuality(code)) onLoginClick()
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
 * 播放信息二级页（A4 极简，随更多菜单同语言）：‹ 返回上一级，音频 / 播放分组 K-V + DSP 状态提示。
 * 只读；未在播（无节目/暂停态未起播）时技术行整组隐藏，只留开关与偏好行 + 一句引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackInfoSheet(
    info: cn.debubu.tingbili.core.media.PipelineInfo,
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
                PlaybackInfoRow("倍速", "${info.speed}x")
            } else {
            // 播放中：管线参数尚未就绪时显示"读取中…"（如切集/缓冲瞬间），不写"未知"吓人。
            MoreGroupTitle("音频")
            val initializing = info.decoderName == null && !info.offloadActive && !info.offloadSleeping
            PlaybackInfoRow("封装/编码", info.mimeType ?: "读取中…")
            val codecs = info.codecs
            if (codecs != null) PlaybackInfoRow("编码参数", codecs)
            // 码率：容器写了就用真实值（AAC/E-AC-3）；没写（Hi-Res FLAC 是 VBR 且容器头
            // 不含码率）先用接口档位标称值兜底；整集命中本地缓存连解析都不发生时，
            // 给终态「无损 VBR」，不能永远停在「读取中…」误导成还在加载
            PlaybackInfoRow(
                "码率",
                when {
                    info.bitrateKbps != null ->
                        "${info.bitrateKbps} kbps" + if (info.bitrateNominal) "（档位标称）" else ""
                    info.mimeType?.startsWith("audio/flac") == true -> "无损 VBR（无固定码率）"
                    else -> "读取中…"
                },
            )
            PlaybackInfoRow("采样率", if (info.sampleRateHz > 0) "${info.sampleRateHz} Hz" else "读取中…")
            PlaybackInfoRow("声道", if (info.channelCount > 0) info.channelCount.toString() else "读取中…")
            MoreGroupTitle("播放")
            PlaybackInfoRow("解码器", info.decoderName ?: if (info.offloadActive) "DSP 芯片直接解码（不经过 CPU）" else "读取中…")
            PlaybackInfoRow("解码方式", if (info.offloadActive) "DSP 硬件直解（功耗最低）" else if (initializing) "读取中…" else info.decoderKind)
            PlaybackInfoRow(
                "DSP 卸载",
                when {
                    !info.offloadEnabled -> "已关闭（可在设置里打开，灭屏听歌更省电）"
                    info.offloadActive && info.offloadSleeping -> "生效中：低功耗芯片解码，主芯片已休眠"
                    info.offloadActive -> "生效中：低功耗芯片解码，主芯片可休眠"
                    else -> "已开启，当前音频不支持，已自动回落"
                }
            )
            PlaybackInfoRow("倍速", "${info.speed}x")
            if (info.offloadEnabled) {
                Spacer(Modifier.height(4.dp))
                if (info.offloadActive) {
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

@Composable
private fun AudioQualitySheet(
    options: PlayerViewModel.AudioOptionsUi,
    onRetry: () -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    showLoginHint: Boolean,
    onLoginClick: () -> Unit,
) {
    TingBiliBottomSheet(
        title = "切换音质",
        subtitle = "只列出本视频实际提供的档位（从高到低）；换档不断播。",
        onDismissRequest = onDismiss,
    ) {
        when (options) {
            is PlayerViewModel.AudioOptionsUi.Hidden,
            is PlayerViewModel.AudioOptionsUi.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            is PlayerViewModel.AudioOptionsUi.Error -> {
                Text(
                    text = options.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry) { Text("重试") }
            }
            is PlayerViewModel.AudioOptionsUi.Ready -> {
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    options.options.forEach { item ->
                        SettingRadioRow(
                            title = item.title,
                            subtitle = item.subTitle.takeIf { it.isNotBlank() },
                            selected = item.selected,
                            onClick = { onSelect(item.code) },
                        )
                    }
                    if (showLoginHint) {
                        Surface(
                            onClick = onLoginClick,
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("登录解锁更高音质", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "192K / 杜比 / Hi-Res 需登录（部分需大会员）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("去登录 ›", color = MaterialTheme.colorScheme.primary)
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
                // 右侧只剩 ⋮ 一个动作位，左右留白同步收窄，长合集名多露一行
                .padding(horizontal = 56.dp, vertical = 6.dp),
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

/**
 * 章节标题行：左侧标题列 + 右侧固定爱心（方案 A）。
 * 长标题时文字列内部省略（两行截断 + UP 名单行截断），爱心 48dp 不参与挤压；
 * 点标题进章节目录看全文。UP 名可点进主页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerChapterHeader(
    chapterLabel: String,
    author: String?,
    favoriteAvailable: Boolean,
    isFavorite: Boolean,
    onChapterClick: () -> Unit,
    onAuthorClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onFavoriteLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = chapterLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onChapterClick),
            )
            // UP 名可点：进 UP 主主页（Track 不带 mid，由 VM 走 view 接口解析）
            author?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onAuthorClick() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "UP主 · $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "进入 UP 主主页",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (favoriteAvailable) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClickLabel = if (isFavorite) "取消收藏到我的最爱" else "收藏到我的最爱",
                        onLongClickLabel = "选择收藏夹",
                        onClick = onFavoriteClick,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onFavoriteLongClick()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    // 外层 combinedClickable 已设 onClickLabel/onLongClickLabel，这里再给描述会被 TalkBack 念两遍（REVIEW_REPORT H-04）
                    contentDescription = null,
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * 进度条的真实订阅点：1Hz 进度只让这一块重组，播放页 body 不再每秒重跑（REVIEW_REPORT B-03）。
 */
@Composable
private fun ProgressBarSection(vm: PlayerViewModel, enabled: Boolean) {
    val progUi by vm.progressUi.collectAsStateWithLifecycle()
    PlaybackProgressBar(
        positionMs = progUi.positionMs,
        bufferedPositionMs = progUi.bufferedPositionMs,
        durationMs = progUi.durationMs,
        enabled = enabled,
        onSeek = vm::seekTo,
    )
}

/** 辅助栏的睡眠定时文案每秒变：订阅下沉到这里，避免整页 body 跟着重跑。 */
@Composable
private fun PlayerAuxBarSection(
    vm: PlayerViewModel,
    speedLabel: String,
    qualityLabel: String,
    onTimerClick: () -> Unit,
    onChapterClick: () -> Unit,
    onQualityClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val progUi by vm.progressUi.collectAsStateWithLifecycle()
    PlayerAuxBar(
        sleepLabel = sleepLabel(progUi.sleepRemainSec, progUi.sleepEndOfChapter),
        sleepActive = progUi.sleepRemainSec != null || progUi.sleepEndOfChapter,
        speedLabel = speedLabel,
        qualityLabel = qualityLabel,
        onTimerClick = onTimerClick,
        onChapterClick = onChapterClick,
        onQualityClick = onQualityClick,
        onSpeedClick = onSpeedClick,
        onInfoClick = onInfoClick,
    )
}

/** 定时弹层：只在打开时组合，倒计时每秒刷新也只影响弹层。 */
@Composable
private fun SleepTimerSheetSection(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val progUi by vm.progressUi.collectAsStateWithLifecycle()
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
        onDismiss = onDismiss,
    )
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

    Column(modifier = Modifier.fillMaxWidth()) {
        // 自绘进度线：线画在底部 5dp（时间数字紧贴进度线，时间在下，整体为一个模块），
        // 可点/可拖区域取 32dp：细长条进度线 48dp 留白过多、视觉头重脚轻，
        // 故相对 REVIEW_REPORT H-02 的 48dp 下限做例外（语义与手势范围不变，仅压缩透明区）。
        // 省出的高度由上方歌词区的 weight 吸收，多显示约半行歌词。
        val canSeek = enabled && safeDuration > 0L
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics {
                    if (canSeek) {
                        progressBarRangeInfo = ProgressBarRangeInfo(shownRatio, 0f..1f)
                        setProgress { target ->
                            onSeek((target.coerceIn(0f, 1f) * safeDuration).toLong())
                            true
                        }
                    }
                }
                .pointerInput(canSeek, safeDuration) {
                    if (!canSeek) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragRatio = (down.position.x / size.width).coerceIn(0f, 1f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                dragRatio?.let { onSeek((it * safeDuration).toLong()) }
                                dragRatio = null
                                break
                            }
                            if (change.positionChanged()) {
                                dragRatio = (change.position.x / size.width).coerceIn(0f, 1f)
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 细线风：与 mini 播放器顶部的 2.dp 进度线统一，无常驻圆点
                // 时间在下方，线取底部 5dp 以保持紧贴（与原来顶部 5dp 镜像）
                val centerY = size.height - 5.dp.toPx()
                val baseHeight = 3.dp.toPx()
                val buffered = bufferedRatio.coerceAtLeast(shownRatio).coerceIn(0f, 1f)

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
                    topLeft = Offset(0f, centerY - baseHeight / 2f),
                    size = Size(size.width * shownRatio, baseHeight),
                    cornerRadius = CornerRadius(baseHeight / 2f),
                )
                // 只在手指拖动时画手柄点，静止时不占位、不晃
                if (dragRatio != null) {
                    drawCircle(
                        color = activeColor,
                        radius = 5.dp.toPx(),
                        center = Offset(size.width * shownRatio, centerY),
                    )
                }
            }
        }

        // 时间行在进度线下方：与进度线为同一模块，紧贴（0dp），不另加间距。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatMs(shownPositionMs) + " / " + formatMs(durationMs),
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
    remainSec != null -> String.format(java.util.Locale.ROOT, "%d:%02d", remainSec / 60, remainSec % 60)
    else -> "定时"
}

@Composable
private fun PlayerAuxBar(
    sleepLabel: String,
    sleepActive: Boolean,
    speedLabel: String,
    qualityLabel: String,
    onTimerClick: () -> Unit,
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

/** 封面态实时字幕卡：撑满剩余高度，上2下6窗口 + 当前行自动跟随；空态居中。点击行为按字幕状态分流 */
@Composable
private fun MiniLyricCard(
    lyrics: List<cn.debubu.tingbili.data.bilibili.model.LyricLine>,
    currentIndex: Int,
    status: SubtitleLoadState,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    trackCount: Int = 0,
    onSwitchTrack: () -> Unit = {},
    fontScale: Float = 1f,
) {
    val current = lyrics.getOrNull(currentIndex)?.text
    Surface(
        onClick = onCardClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val canSwitch = status == SubtitleLoadState.Ready && trackCount > 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ClosedCaption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                // 标题写真实轨道名（AI 轨由 label 自带标注）；多轨时可点这里切换，不再占用底部辅助栏
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (canSwitch) {
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onSwitchTrack)
                            .padding(horizontal = 2.dp)
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        text = label ?: "字幕",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (canSwitch) {
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "切换字幕",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
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
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "字幕加载中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    }
                }
                SubtitleLoadState.NoAuth -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                    Text(
                        text = "登录后查看字幕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    }
                }
                SubtitleLoadState.Error -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                    Text(
                        text = "字幕加载失败，点击重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    }
                }
                SubtitleLoadState.Empty -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                    Text(
                        text = "暂无字幕",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                }
                SubtitleLoadState.Ready -> if (current == null) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无字幕",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 窗口固定"上 2 下 7"共 10 行：锚点取窗口首行，内容超出视口时不会被
                    // 滚动尾部钳制，字体放大后上方上下文仍保留。底部约一行白，10 行刚好填满。
                    TingBiliLyricList(
                        items = lyrics,
                        currentIndex = currentIndex,
                        windowSize = 10,
                        key = { idx, line -> line.timeMs * 100 + idx },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { _, line, tone ->
                        if (line.text.isNotBlank()) {
                            val isCurrent = tone == LyricTone.Current
                            Text(
                                text = line.text,
                                style = if (isCurrent) {
                                    MaterialTheme.typography.titleMedium.copy(
                                        fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale,
                                    )
                                } else {
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale,
                                    )
                                },
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = when (tone) {
                                    LyricTone.Current -> MaterialTheme.colorScheme.primary
                                    LyricTone.Near -> MaterialTheme.colorScheme.onSurfaceVariant
                                    LyricTone.Faded ->
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 2,
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

/**
 * 全屏字幕：当前行主题色大字 + 上下文小字灰，当前行锚在视口 1/3 高度跟随，末尾带 AI 标注。
 *
 * 交互：点文字 = 跳到该行；点行以外的空白（上下留白、行间隙）= 收起全屏。
 * 点击区域只到文字边界，行两侧空白不再算“点中”，从根上减少误触跳转。
 *
 * 滚动、锚定、拖动接管逻辑与小卡共用 [TingBiliLyricList]，差异只在窗口与点击行为。
 */
@Composable
private fun FullscreenLyricsList(
    state: PlayerViewModel.LyricUiState,
    onSeekLyric: (cn.debubu.tingbili.data.bilibili.model.LyricLine) -> Unit,
    onExit: () -> Unit,
    isLoading: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 空白处单击收起全屏：用 clickable 而不是 detectTapGestures——
            // 后者会在父层提前消费 down，抢掉子项（歌词行）的点击。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "收起全屏字幕",
            ) { onExit() },
    ) {
        TingBiliLyricList(
            items = state.lyrics,
            currentIndex = state.currentLyricIndex,
            // timeMs 会重（AI 字幕同秒多行），key 必须唯一否则复用错位连带滚错位置
            key = { idx, line -> "$idx-${line.timeMs}" },
            anchorRatio = 1f / 3f,
            footer = if (state.lyrics.isNotEmpty() && state.subtitleLabel != null) {
                {
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
            } else {
                null
            },
        ) { _, line, tone ->
            val isCurrent = tone == LyricTone.Current
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
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = when (tone) {
                    LyricTone.Current -> MaterialTheme.colorScheme.primary
                    LyricTone.Near -> MaterialTheme.colorScheme.onSurfaceVariant
                    LyricTone.Faded -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 不铺满整行：只有文字（含少量内边距）可点，行两侧空白留给“点空白退出”
                // （小卡是 fillMaxWidth 整行宽，这里为保退出手势不能照搬）
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isLoading) { onSeekLyric(line) }
                    .padding(horizontal = 12.dp, vertical = if (isCurrent) 6.dp else 2.dp),
            )
        }
        // 顶部药丸已去掉（会压住第一屏字幕）：退出靠点空白处，读屏走外层容器的“收起全屏字幕”操作
    }
}

/** 歌词行跳转的撤销快照：fromMs=跳转前位置，token 用于丢弃过期的自动消失 */
private data class JumpUndo(val fromMs: Long, val toLabel: String, val token: Long)

/** 撤销提示停留时长：够用户反应过来点“撤销”，又不至于长期遮挡 */
private const val UNDO_HINT_MS = 4_000L

/** 跳转提示：给误触一个“撤销”出口，超时自动消失 */
@Composable
private fun UndoJumpHint(
    label: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onUndo,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(text = "已跳到 $label", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "撤销",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.inversePrimary,
            )
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
    // 自定义初值：运行中沿用本次设定，否则上次所选、无记忆则 60；钳制到 30~600，不再贴 30 分钟格（45 也能原样回来）
    // 带 key：冷启动时 flow 初值尚未落盘重组后要用最新值重算（无 key 会钉死初值 60/false）
    val initialMin = (totalMin ?: lastMin).coerceIn(SLEEP_MIN_MIN, SLEEP_MAX_MIN)
    var customText by remember(totalMin, lastMin) { mutableStateOf(initialMin.toString()) }
    var customOpen by remember(totalMin, lastMin) { mutableStateOf(initialMin !in SLEEP_PRESETS) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var finish by remember(remainSec != null, endOfChapter, finishDefault) {
        mutableStateOf(if (remainSec != null) finishChapter else finishDefault)
    }
    val customError = sleepCustomError(customText)
    val validMin = customText.toIntOrNull()?.takeIf { customError == null }
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
                // Hero：巨大分钟数 + 预计停止时刻（排版锚点，不再用 常用/自定义 小标题表单）
                val endClock = validMin?.let { sleepEndClockLabel(it) }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = validMin?.toString() ?: "—",
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 76.sp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "分钟",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    Text(
                        text = endClock?.let { "约 $it 后自动停止" } ?: (customError ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (customError != null && validMin == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 预设 3x2：5 档 + 自定义（240 不再孤儿一行）
                val customActive = validMin !in SLEEP_PRESETS
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(listOf(30, 60, 90), listOf(120, 240, null)).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { preset ->
                                if (preset == null) {
                                    FilterChip(
                                        selected = customActive,
                                        onClick = {
                                            customOpen = true
                                            scope.launch { focusRequester.requestFocus() }
                                        },
                                        label = {
                                            Text(
                                                "自定义",
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    FilterChip(
                                        selected = validMin == preset,
                                        onClick = {
                                            customText = preset.toString()
                                            customOpen = false
                                        },
                                        label = {
                                            Text(
                                                "$preset 分钟",
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
                // 自定义行：仅自定义模式展开；圆形图标步进替代原来的 -10/+10 文字按钮
                if (customOpen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it.filter(Char::isDigit).take(4) },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            singleLine = true,
                            isError = customError != null,
                            supportingText = {
                                Text(customError ?: "30~600 的任意整数")
                            },
                            suffix = { Text("分钟") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                validMin?.let { onSelectMinutes(it, finish); onDismiss() }
                            }),
                        )
                        FilledTonalIconButton(onClick = {
                            val base = customText.toIntOrNull() ?: SLEEP_MIN_MIN
                            customText = (base - 10).coerceIn(SLEEP_MIN_MIN, SLEEP_MAX_MIN).toString()
                        }) { Icon(Icons.Filled.Remove, contentDescription = "减 10 分钟") }
                        FilledTonalIconButton(onClick = {
                            val base = customText.toIntOrNull() ?: SLEEP_MIN_MIN
                            customText = (base + 10).coerceIn(SLEEP_MIN_MIN, SLEEP_MAX_MIN).toString()
                        }) { Icon(Icons.Filled.Add, contentDescription = "加 10 分钟") }
                    }
                }
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
                    onClick = { validMin?.let { onSelectMinutes(it, finish); onDismiss() } },
                    enabled = validMin != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (running) "更新定时（${validMin ?: "—"} 分钟）" else "开始定时（${validMin ?: "—"} 分钟）")
                }
            }
        }
    }
}

/** 睡眠定时：30~600 分钟，预设 30/60/90/120/240 + 自定义任意整数 */
internal const val SLEEP_MIN_MIN = 30
internal const val SLEEP_MAX_MIN = 600
internal val SLEEP_PRESETS = listOf(30, 60, 90, 120, 240)

/** 自定义输入校验：null=合法，否则为错误文案（超限时禁用确认，不做静默钳制） */
internal fun sleepCustomError(text: String): String? {
    if (text.isBlank()) return "请输入分钟数"
    val v = text.toIntOrNull() ?: return "请输入数字"
    if (v !in SLEEP_MIN_MIN..SLEEP_MAX_MIN) return "请输入 ${SLEEP_MIN_MIN}~${SLEEP_MAX_MIN} 分钟"
    return null
}

/** 预计停止时刻 HH:mm（Hero 副标题用，now 可注入便于单测） */
internal fun sleepEndClockLabel(min: Int, now: LocalTime = LocalTime.now()): String {
    val t = now.plusMinutes(min.toLong())
    return "%02d:%02d".format(t.hour, t.minute)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    queue: List<cn.debubu.tingbili.core.data.model.Track>,
    currentIndex: Int,
    sourceTitle: String?,
    playOrder: cn.debubu.tingbili.core.media.PlayOrder,
    shuffleEnabled: Boolean,
    onPlayOrderChange: (cn.debubu.tingbili.core.media.PlayOrder) -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    // 打开时、切顺序、随机跳到新集都定位到当前项（上方留两条上下文）
    LaunchedEffect(currentIndex, playOrder) {
        if (currentIndex in queue.indices) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = "播放列表（${queue.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            sourceTitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "来自「$it」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = playOrder == cn.debubu.tingbili.core.media.PlayOrder.FORWARD,
                        onClick = { onPlayOrderChange(cn.debubu.tingbili.core.media.PlayOrder.FORWARD) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("正序") },
                    )
                    SegmentedButton(
                        selected = playOrder == cn.debubu.tingbili.core.media.PlayOrder.REVERSE,
                        onClick = { onPlayOrderChange(cn.debubu.tingbili.core.media.PlayOrder.REVERSE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("倒序") },
                    )
                }
                FilterChip(
                    selected = shuffleEnabled,
                    onClick = { onShuffleChange(!shuffleEnabled) },
                    label = { Text("随机播放") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(bottom = 32.dp),
        ) {
            itemsIndexed(items = queue, key = { _, track -> "${track.bvid}:${track.cid}" }) { idx, track ->
                ChapterRow(
                    index = idx,
                    track = track,
                    selected = idx == currentIndex,
                    onClick = { onSelect(idx) },
                )
            }
        }
    }
}

/**
 * 播放列表行：与收藏夹列表（`FavoriteTrackRow`）同一套信息层级，
 * 序号 / 主标题 / `P{n} · BV 标题` / 时长，正在播的一行高亮。
 */
@Composable
private fun ChapterRow(
    index: Int,
    track: cn.debubu.tingbili.core.data.model.Track,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = String.format(java.util.Locale.ROOT, "%02d", index + 1),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(32.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = track.title.ifBlank { track.bvid },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            trackMetaLabel(track.title, track.videoTitle, track.pageIndex)?.let { meta ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        track.durationMs.takeIf { it > 0L }?.let { durationMs ->
            Text(
                text = formatDurationMs(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SubtitleSheet(
    tracks: List<cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    TingBiliBottomSheet(
        title = "切换字幕",
        subtitle = "点击字幕轨后立即切换。",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            tracks.forEachIndexed { index, track ->
                SettingRadioRow(
                    title = track.displayLabel,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

