package cn.debubu.tingbili.feature.history

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.playedProgressLabel
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@Composable
fun HistoryScreen(
    vm: HistoryViewModel = hiltViewModel()
) {
    val list by vm.history.collectAsStateWithLifecycle()
    val info by vm.videoInfo.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "历史记录",
                subtitle = if (list.isNotEmpty()) "共 ${list.size} 条播放记录" else null,
                actions = {
                    if (list.isNotEmpty()) {
                        TextButton(onClick = { vm.clearAll() }) { Text("清空") }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (list.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无播放记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { "${it.bvid}:${it.cid}" }) { item ->
                    val track = info[item.bvid].orEmpty().firstOrNull { it.cid == item.cid }
                    HistoryRow(
                        entity = item,
                        track = track,
                        onResume = { vm.resume(item) },
                        onDelete = { vm.delete(item.bvid, item.cid) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entity: HistoryEntity,
    track: Track?,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    val imageFormat = LocalImageFormat.current
    // 主标题用 BV 总标题（“在听哪本书”）；章节行放分 P 名/UP主，避免和主标题重复
    val bvTitle = track?.videoTitle?.takeIf { it.isNotBlank() }
        ?: track?.title?.takeIf { it.isNotBlank() }
        ?: entity.bvid
    val chapterText = historyChapterLine(track, bvTitle)

    Card(
        onClick = onResume,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track?.cover
                    ?.takeIf { it.isNotBlank() }
                    ?.biliImage(BiliImageVariant.ListThumb, imageFormat),
                contentDescription = null,
                modifier = Modifier
                    .width(108.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bvTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chapterText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatDate(entity.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "上次听到 " +
                        playedProgressLabel(track?.pageIndex, entity.positionMs, track?.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                track?.durationMs?.takeIf { it > 0 }?.let { duration ->
                    Spacer(modifier = Modifier.height(6.dp))
                    val fraction = (entity.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 章节行：`P7 · 云南虫谷07 · 肉饼搞机`。
 * 分 P 名常以 BV 总标题开头（如“国风版鬼吹灯第四季·云南虫谷07”），会剥掉这段重复前缀；
 * 剥完只剩纯数字（`07`）时整段省掉，避免出现“P7 · 07”。
 */
private fun historyChapterLine(track: Track?, bvTitle: String): String {
    if (track == null) return ""
    val name = track.title.trim()
    val redundant = bvTitle.isNotBlank() && name.startsWith(bvTitle)
    val short = if (redundant) {
        name.removePrefix(bvTitle).trim(' ', '·', '-', '_', '.', '　')
    } else {
        name
    }
    val keepName = short.isNotBlank() && short.any { !it.isDigit() } && short != bvTitle
    return buildList {
        val page = track.pageIndex
        if (page != null && page > 0 && track.pageCount > 1) {
            add(if (keepName) "P$page · $short" else "P$page")
        } else if (keepName) {
            add(short)
        }
        track.author.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" · ")
}

private fun formatDate(epochMs: Long): String {
    return try {
        val sdf = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        sdf.format(java.util.Date(epochMs))
    } catch (_: Exception) {
        "$epochMs"
    }
}
