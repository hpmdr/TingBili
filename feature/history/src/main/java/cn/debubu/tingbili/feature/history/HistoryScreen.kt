package cn.debubu.tingbili.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.HistoryEntity

@Composable
fun HistoryScreen(
    vm: HistoryViewModel = hiltViewModel()
) {
    val list by vm.history.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "播放记录 (${list.size})", style = MaterialTheme.typography.titleMedium)
            if (list.isNotEmpty()) {
                TextButton(onClick = { vm.clearAll() }) { Text("清空") }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无播放记录", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { "${it.bvid}:${it.cid}" }) { item ->
                    HistoryRow(
                        entity = item,
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
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entity.bvid, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "cid ${entity.cid} · ${formatMs(entity.positionMs)} · ${formatDate(entity.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
                Button(onClick = onResume) { Text("续播") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Progress: HistoryEntity has no duration; show indeterminate if unknown, or small fraction based on position modulo
            // We display a simple progress bar at 0.5 if position > 0 to indicate resumable, else 0
            val progress = if (entity.positionMs > 0L) 0.5f else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

private fun formatDate(epochMs: Long): String {
    return try {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(epochMs))
    } catch (_: Exception) {
        "$epochMs"
    }
}
