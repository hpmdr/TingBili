package cn.debubu.tingbili.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    vm: PlaylistViewModel = hiltViewModel()
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val selectedId by vm.selectedPlaylistId.collectAsStateWithLifecycle()

    var newName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Create section
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text("新建歌单名称") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        vm.create(newName.trim())
                        newName = ""
                    }
                }
            ) { Text("创建") }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Playlists overview
        Text(text = "歌单 (${playlists.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(0.5f)
        ) {
            items(playlists, key = { it.id }) { pl ->
                PlaylistCard(
                    playlist = pl,
                    isSelected = pl.id == selectedId,
                    onSelect = { vm.selectPlaylist(pl.id) },
                    onPlayAll = { vm.playAll(pl.id) },
                    onDelete = { vm.deletePlaylist(pl.id) }
                )
            }
            if (playlists.isEmpty()) {
                item { Text("暂无歌单，创建一个吧", modifier = Modifier.padding(8.dp), color = Color.Gray) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tracks detail for selected playlist
        if (selectedId != null) {
            val pid = selectedId!!
            val title = playlists.firstOrNull { it.id == pid }?.name ?: "歌单详情"
            Text(text = "$title · ${tracks.size} 首", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.playAll(pid) }, enabled = tracks.isNotEmpty()) { Text("全部播放") }
                TextButton(onClick = { vm.clearTracks(pid) }, enabled = tracks.isNotEmpty()) { Text("清空") }
            }
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(0.5f)
            ) {
                itemsIndexed(tracks, key = { _, item -> "${item.playlistId}:${item.bvid}:${item.cid}" }) { idx, entity ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                                vm.removeTrack(pid, entity.bvid, entity.cid)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Red)
                                    .padding(12.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.White)
                            }
                        }
                    ) {
                        TrackItemRow(
                            entity = entity,
                            onMoveUp = { if (idx > 0) vm.reorder(pid, idx, idx - 1) },
                            onMoveDown = { if (idx < tracks.lastIndex) vm.reorder(pid, idx, idx + 1) },
                            onDelete = { vm.removeTrack(pid, entity.bvid, entity.cid) }
                        )
                    }
                }
                if (tracks.isEmpty()) {
                    item { Text("暂无歌曲", modifier = Modifier.padding(8.dp), color = Color.Gray) }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(0.5f), contentAlignment = Alignment.Center) {
                Text("请选择一个歌单查看详情", color = Color.Gray)
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPlayAll: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = playlist.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = "id: ${playlist.id}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            IconButton(onClick = onPlayAll) {
                Icon(Icons.Default.PlayArrow, contentDescription = "播放全部")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除歌单")
            }
        }
    }
}

@Composable
private fun TrackItemRow(
    entity: PlaylistTrackEntity,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "拖拽",
                modifier = Modifier.padding(end = 8.dp),
                tint = Color.Gray
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entity.title.ifBlank { "${entity.bvid} P${entity.cid}" }, style = MaterialTheme.typography.bodyMedium)
                Text(text = "${entity.bvid} · cid ${entity.cid} · order ${entity.order}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            // Reorder controls (拖拽排序 — 点击上下移动，拖拽手势由 LazyColumn + pointerInput 扩展)
            Column {
                TextButton(onClick = onMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown) { Text("↓") }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}
