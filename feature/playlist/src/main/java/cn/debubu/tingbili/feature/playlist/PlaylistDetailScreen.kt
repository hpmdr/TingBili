package cn.debubu.tingbili.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit = {},
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showRename by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    if (playlist == null) {
        //  playlist 被删除或不存在
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pl = playlist!!

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = pl.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { showMenu = false; showRename = true })
                    DropdownMenuItem(text = { Text("删除听单") }, onClick = { showMenu = false; viewModel.deletePlaylist(onBack) })
                }
            }
        }

        if (showRename) {
            RenameDialog(
                currentName = pl.name,
                onConfirm = { viewModel.rename(it); showRename = false },
                onDismiss = { showRename = false }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
        ) {
            // 大封面 + 信息
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    if (pl.cover.isNullOrBlank()) {
                        Box(
                            modifier = Modifier.size(110.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Album, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                        }
                    } else {
                        AsyncImage(
                            model = pl.cover,
                            contentDescription = null,
                            modifier = Modifier.size(110.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = pl.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "${tracks.size} 集", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = formatDate(pl.createdAt), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.playAll(0) }, enabled = tracks.isNotEmpty()) { Text("全部播放") }
                            TextButton(onClick = { viewModel.clearAll() }, enabled = tracks.isNotEmpty()) { Text("清空") }
                        }
                    }
                }
            }

            // 表头
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "集列表 · ${tracks.size} 集", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
            }

            itemsIndexed(tracks, key = { _, item -> "${item.playlistId}:${item.bvid}:${item.cid}" }) { idx, entity ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { v ->
                        if (v == SwipeToDismissBoxValue.EndToStart || v == SwipeToDismissBoxValue.StartToEnd) {
                            viewModel.remove(entity); true
                        } else false
                    }
                )
                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Red).padding(12.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.White)
                        }
                    }
                ) {
                    DetailTrackRow(
                        index = idx,
                        entity = entity,
                        onClick = { viewModel.playAt(idx) },
                        onMoveUp = { if (idx > 0) viewModel.reorder(idx, idx - 1) },
                        onMoveDown = { if (idx < tracks.lastIndex) viewModel.reorder(idx, idx + 1) },
                        onDelete = { viewModel.remove(entity) }
                    )
                }
            }

            if (tracks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无内容", color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("去首页添加一个有声书吧", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DetailTrackRow(
    index: Int,
    entity: PlaylistTrackEntity,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(28.dp)
            )
            Icon(Icons.Default.DragHandle, contentDescription = "拖拽", tint = Color.Gray, modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entity.title.ifBlank { "${entity.bvid} · ${entity.cid}" }, style = MaterialTheme.typography.bodyMedium)
                Text(text = "${entity.bvid} · cid ${entity.cid}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Column {
                TextButton(onClick = onMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown) { Text("↓") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除") }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名听单") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("听单名称") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatDate(epochMs: Long): String = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))
} catch (_: Exception) { "$epochMs" }
