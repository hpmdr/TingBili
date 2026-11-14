package cn.debubu.tingbili.feature.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.BvFavoritePageEntity
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.MiniPlayerClearance
import cn.debubu.tingbili.core.ui.component.BackNavIcon
import cn.debubu.tingbili.core.ui.component.FavoriteFolderMultiPickerSheet
import cn.debubu.tingbili.core.ui.component.TingBiliMessageBus
import cn.debubu.tingbili.core.ui.component.FavoriteFolderOption
import cn.debubu.tingbili.core.ui.component.TingBiliConfirmDialog
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BvFavoriteScreen(
    bvid: String,
    onBack: () -> Unit,
    onPlayNavigate: () -> Unit = {},
    vm: BvFavoriteViewModel = hiltViewModel(),
) {
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val cachedTracks by vm.cachedTracks.collectAsStateWithLifecycle()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val startingPlay by vm.startingPlay.collectAsStateWithLifecycle()
    val pendingIndex by vm.pendingIndex.collectAsStateWithLifecycle()
    val playingTrack by vm.playingTrack.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(bvid) { vm.refresh() }
    // 一次性提示走全局总线：发消息即可，显示位置/队列由壳层唯一宿主负责
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            TingBiliMessageBus.show(it)
        }
    }

    val refreshing = refreshState is BvRefreshState.Refreshing
    val fav = favorite

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "BV 收藏",
                navigationIcon = { BackNavIcon(onBack = onBack) },
                actions = {
                    IconButton(
                        onClick = { vm.refresh() },
                        enabled = !refreshing,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                enabled = !refreshing,
                                onClick = {
                                    showMenu = false
                                    vm.refresh()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "取消收藏",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showCancel = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (fav == null) {
            when (val state = refreshState) {
                BvRefreshState.Idle, BvRefreshState.Refreshing -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is BvRefreshState.Failed -> BvErrorState(
                    reason = state.reason,
                    invalid = state.invalid,
                    onRetry = { vm.refresh() },
                    onBack = onBack,
                    modifier = Modifier.padding(padding),
                )

                BvRefreshState.Success -> BvErrorState(
                    reason = "未收藏该 BV，或本地缓存不存在",
                    invalid = false,
                    onRetry = { vm.refresh() },
                    onBack = onBack,
                    modifier = Modifier.padding(padding),
                )
            }
            return@TingBiliScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp + MiniPlayerClearance),
        ) {
            item(key = "header") {
                BvHeader(favorite = fav, lastSyncedAt = fav.lastSyncedAt)
            }
            item(key = "sync") {
                when (val state = refreshState) {
                    BvRefreshState.Refreshing -> Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "正在同步最新内容…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is BvRefreshState.Failed -> BvFailureBanner(
                        state = state,
                        lastSyncedAt = fav.lastSyncedAt,
                        onRetry = { vm.refresh() },
                    )

                    else -> Unit
                }
            }
            item(key = "actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { vm.playAll(focus?.index ?: 0); onPlayNavigate() },
                        enabled = pages.isNotEmpty() && !startingPlay,
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (startingPlay) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("准备中…")
                        } else {
                            Icon(
                                if (focus != null) Icons.Default.Replay else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (focus != null) "继续播放" else "全部播放")
                        }
                    }
                    OutlinedButton(
                        onClick = { showPicker = true },
                        enabled = pages.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("加入收藏夹")
                    }
                }
            }
            item(key = "list_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("分P", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${pages.size} P",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "暂无分P",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            } else {
                itemsIndexed(
                    pages,
                    key = { _, page -> "${page.bvid}:${page.cid}" },
                ) { index, page ->
                    FavoriteTrackRow(
                        index = index,
                        entity = page.toDisplayEntity(fav),
                        played = progress["${page.bvid}:${page.cid}"],
                        isPending = startingPlay && pendingIndex == index,
                        focused = focus?.index == index,
                        playing = playingTrack?.takeIf { it.key == "${page.bvid}:${page.cid}" },
                        onClick = { vm.playAt(index); onPlayNavigate() },
                        showVideoTitle = false,
                    )
                }
            }
        }
    }

    if (showCancel && fav != null) {
        TingBiliConfirmDialog(
            title = "取消 BV 收藏？",
            text = "将删除「${fav.title}」及其本地缓存内容。",
            confirmLabel = "取消收藏",
            destructive = true,
            onConfirm = {
                showCancel = false
                vm.removeFavorite(onBack)
            },
            onDismiss = { showCancel = false },
        )
    }

    if (showPicker && fav != null) {
        FavoriteFolderMultiPickerSheet(
            title = "收藏到收藏夹",
            subtitle = fav.title,
            tracks = cachedTracks,
            folders = folders.map { FavoriteFolderOption(it.id, it.name, it.isDefault) },
            onDismiss = { showPicker = false },
            onConfirm = { tracks, folderIds ->
                vm.addToFolders(folderIds, tracks)
                showPicker = false
            },
            onCreateFolder = { name, tracks ->
                vm.createFolderAndAdd(name, tracks)
                showPicker = false
            },
        )
    }
}

@Composable
private fun BvHeader(favorite: BvFavoriteEntity, lastSyncedAt: Long?) {
    val imageFormat = LocalImageFormat.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favorite.cover.isBlank()) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            AsyncImage(
                model = favorite.cover.biliImage(BiliImageVariant.ListThumb, imageFormat),
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = favorite.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (favorite.author.isNotBlank()) append(favorite.author).append(" · ")
                    append("${favorite.pageCount} P")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "收藏于 " + formatDate(favorite.addedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lastSyncedAt?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "上次同步 " + formatDateTime(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BvFailureBanner(
    state: BvRefreshState.Failed,
    lastSyncedAt: Long?,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = if (state.invalid) {
                    "BV 已失效或不可见，以下为缓存内容"
                } else {
                    "刷新失败：${state.reason}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (state.invalid && state.reason.isNotBlank()) {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            lastSyncedAt?.let {
                Text(
                    text = "上次同步 " + formatDateTime(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun BvErrorState(
    reason: String,
    invalid: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (invalid) "BV 已失效或不可见" else "加载失败",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("重试") }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}

private fun BvFavoritePageEntity.toDisplayEntity(parent: BvFavoriteEntity): FavoriteTrackEntity =
    FavoriteTrackEntity(
        folderId = 0L,
        bvid = parent.bvid,
        cid = cid,
        title = title,
        author = parent.author,
        cover = parent.cover,
        durationMs = durationMs,
        videoTitle = parent.title,
        pageIndex = pageIndex,
        order = order,
    )

private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatDateTime(epochMs: Long): String = try {
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        .format(DATE_TIME_FORMATTER)
} catch (_: Exception) {
    "$epochMs"
}
