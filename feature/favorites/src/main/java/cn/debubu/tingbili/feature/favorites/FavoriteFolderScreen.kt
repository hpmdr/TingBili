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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.MiniPlayerClearance
import cn.debubu.tingbili.core.ui.component.BackNavIcon
import cn.debubu.tingbili.core.ui.component.CreateFolderDialog
import cn.debubu.tingbili.core.ui.component.TingBiliMessageBus
import cn.debubu.tingbili.core.ui.component.TingBiliConfirmDialog
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@Composable
fun FavoriteFolderScreen(
    onBack: () -> Unit,
    vm: FavoriteFolderViewModel = hiltViewModel(),
) {
    val folder by vm.folder.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val startingPlay by vm.startingPlay.collectAsStateWithLifecycle()
    val pendingIndex by vm.pendingIndex.collectAsStateWithLifecycle()
    val playingTrack by vm.playingTrack.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<FavoriteTrackEntity?>(null) }

    // 一次性提示走全局总线：发消息即可，显示位置/队列由壳层唯一宿主负责
    LaunchedEffect(message) {
        message?.let {
            vm.consumeMessage()
            TingBiliMessageBus.show(it)
        }
    }

    val isDefault = folder?.isDefault == true

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = folder?.name ?: "收藏夹",
                navigationIcon = { BackNavIcon(onBack = onBack) },
                actions = {
                    if (folder != null && !isDefault) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    onClick = {
                                        showMenu = false
                                        showRename = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "删除收藏",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = folder
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@TingBiliScaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp + MiniPlayerClearance),
        ) {
            item(key = "header") {
                FolderHeader(
                    folder = current,
                    tracks = tracks,
                )
            }
            item(key = "play") {
                Button(
                    onClick = { vm.playAll(focus?.index ?: 0) },
                    enabled = tracks.isNotEmpty() && !startingPlay,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
            item(key = "list_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("集列表", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${tracks.size} 集",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (tracks.isEmpty()) {
                item(key = "empty") {
                    EmptyFolderState(isDefault = current.isDefault)
                }
            } else {
                itemsIndexed(
                    tracks,
                    key = { _, entity -> "${entity.folderId}:${entity.bvid}:${entity.cid}" },
                ) { index, entity ->
                    FavoriteTrackRow(
                        index = index,
                        entity = entity,
                        played = progress["${entity.bvid}:${entity.cid}"],
                        isPending = startingPlay && pendingIndex == index,
                        focused = focus?.index == index,
                        playing = playingTrack?.takeIf { it.key == "${entity.bvid}:${entity.cid}" },
                        onClick = { vm.playAt(index) },
                        onDelete = { removeTarget = entity },
                    )
                }
            }
        }
    }

    folder?.let { current ->
        if (showRename) {
            CreateFolderDialog(
                title = "重命名收藏夹",
                initialName = current.name,
                confirmLabel = "保存",
                onConfirm = {
                    vm.rename(it)
                    showRename = false
                },
                onDismiss = { showRename = false },
            )
        }
        if (showDelete) {
            TingBiliConfirmDialog(
                title = "删除收藏夹？",
                text = "将删除「${current.name}」及其播放列表，此操作不可撤销。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    showDelete = false
                    vm.deleteFolder(onBack)
                },
                onDismiss = { showDelete = false },
            )
        }
    }

    removeTarget?.let { entity ->
        TingBiliConfirmDialog(
            title = "移除这一集？",
            text = entity.title.ifBlank { entity.bvid },
            confirmLabel = "移除",
            destructive = true,
            onConfirm = {
                vm.remove(entity)
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun FolderHeader(folder: FavoriteFolderEntity, tracks: List<FavoriteTrackEntity>) {
    val imageFormat = LocalImageFormat.current
    val cover = tracks.firstOrNull { it.cover.isNotBlank() }?.cover

    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (folder.isDefault) Icons.Default.Favorite else Icons.Default.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        } else {
            AsyncImage(
                model = cover.biliImage(BiliImageVariant.ListThumb, imageFormat),
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${tracks.size} 集 · " +
                    (if (folder.isDefault) "默认收藏夹" else "自建收藏夹") +
                    " · " + formatDate(folder.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyFolderState(isDefault: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isDefault) Icons.Default.Favorite else Icons.Default.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (isDefault) "还没有收藏，播放页点爱心试试" else "从 BV 详情长按收藏加入，或在播放页点爱心",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
