package cn.debubu.tingbili.feature.detail

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit = {},
    onPlayNavigate: () -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val showPicker by viewModel.showPlaylistPicker.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 操作结果 Toast
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏：返回 + 标题
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }

        when (val s = state) {
            is DetailUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is DetailUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("加载失败：${s.message}", color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.load() }) { Text("重试") }
                }
            }
            is DetailUiState.Success -> {
                DetailContent(
                    video = s.video,
                    tracks = s.tracks,
                    onPlayAll = { viewModel.play(s.tracks, 0); onPlayNavigate() },
                    onAddAll = { viewModel.showPlaylistPicker() },
                    onPlayPage = { idx -> viewModel.play(s.tracks, idx); onPlayNavigate() }
                )
            }
        }
    }

    // 加入听单选择面板：新建听单 或 加入已有听单
    if (showPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissPlaylistPicker() },
            sheetState = sheetState
        ) {
            PlaylistPickerSheet(
                playlists = playlists,
                defaultName = (state as? DetailUiState.Success)?.video?.title.orEmpty(),
                onCreate = { name ->
                    val tracks = (state as? DetailUiState.Success)?.tracks.orEmpty()
                    viewModel.createPlaylistAndAdd(name, tracks)
                },
                onSelect = { pl ->
                    val tracks = (state as? DetailUiState.Success)?.tracks.orEmpty()
                    viewModel.addToPlaylist(pl, tracks)
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlaylistPickerSheet(
    playlists: List<PlaylistEntity>,
    defaultName: String,
    onCreate: (String) -> Unit,
    onSelect: (PlaylistEntity) -> Unit
) {
    var newName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("加入听单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        // 新建听单
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = { Text("新建听单名称", style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onCreate(newName.trim().ifBlank { defaultName })
                    newName = ""
                },
                enabled = true
            ) { Text("新建") }
        }

        Spacer(Modifier.height(12.dp))
        if (playlists.isEmpty()) {
            Text("暂无已有听单", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        } else {
            Text("选择已有听单 (${playlists.size})", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                itemsIndexed(playlists) { _, pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(pl) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pl.cover.isNullOrBlank()) {
                            Box(
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("听", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            }
                        } else {
                            AsyncImage(
                                model = pl.cover,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatDate(pl.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailContent(
    video: ViewData,
    tracks: List<Track>,
    onPlayAll: () -> Unit,
    onAddAll: () -> Unit,
    onPlayPage: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
    ) {
        // 大封面（16:9）
        item {
            AsyncImage(
                model = video.pic.takeIf { it.isNotBlank() },
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }

        // 标题
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        // UP 主行：头像 + 名字 + 分区/发布时间
        item {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = video.owner?.face?.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "UP: ${video.owner?.name.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            if (video.tname.isNotBlank()) append(video.tname)
                            if (video.pubdate > 0) {
                                if (isNotEmpty()) append(" · ")
                                append(formatDate(video.pubdate * 1000))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }

        // 数据行：纯文本，紧凑直观
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    val stat = video.stat
                    append("${formatCount(stat?.view ?: 0)} 播放")
                    if ((stat?.danmaku ?: 0) > 0) append(" · ${formatCount(stat!!.danmaku)} 弹幕")
                    if ((stat?.like ?: 0) > 0) append(" · ${formatCount(stat!!.like)} 点赞")
                    if ((stat?.coin ?: 0) > 0) append(" · ${formatCount(stat!!.coin)} 投币")
                    if ((stat?.favorite ?: 0) > 0) append(" · ${formatCount(stat!!.favorite)} 收藏")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 操作按钮：播放全部 / 加入听单
        item {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors()
                ) { Text(if (tracks.size > 1) "播放全部 (${tracks.size}P)" else "播放") }
                OutlinedButton(
                    onClick = onAddAll,
                    modifier = Modifier.weight(1f)
                ) { Text("加入听单") }
            }
        }

        // 简介
        if (video.desc.isNotBlank()) {
            item {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "简介",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = video.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        // 分 P 列表
        if (tracks.size > 1) {
            item {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "分P列表 (共${tracks.size}P)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
            }
            itemsIndexed(tracks) { idx, t ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayPage(idx) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "P${idx + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(32.dp)
                    )
                    Text(
                        text = t.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatDuration(t.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 12345 -> 1.2万 */
private fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f万".format(n / 10_000.0)
    else -> n.toString()
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatDate(epochMs: Long): String = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))
} catch (_: Exception) {
    "$epochMs"
}
