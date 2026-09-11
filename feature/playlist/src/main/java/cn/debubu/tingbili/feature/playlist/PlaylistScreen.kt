package cn.debubu.tingbili.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import coil.compose.AsyncImage

/**
 * 收藏列表页：仅浏览，点卡片进详情。收藏来自 BV 一键收藏，手动创建预留。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onPlaylistClick: (Long) -> Unit = {},
    vm: PlaylistViewModel = hiltViewModel()
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(text = "收藏 (${playlists.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { pl ->
                PlaylistCard(
                    playlist = pl,
                    onOpenDetail = { onPlaylistClick(pl.id) }
                )
            }
            if (playlists.isEmpty()) {
                item { Text("暂无收藏，去视频详情收藏一个吧", modifier = Modifier.padding(8.dp), color = Color.Gray) }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    onOpenDetail: () -> Unit
) {
    Card(
        onClick = onOpenDetail,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 收藏封面：BV 收藏为视频封面，空为占位
            if (playlist.cover.isNullOrBlank()) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Album, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(28.dp))
                }
            } else {
                AsyncImage(
                    model = playlist.cover,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = playlist.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = formatDate(playlist.createdAt), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

        }
    }
}

private fun formatDate(epochMs: Long): String = try {
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
} catch (_: Exception) {
    "$epochMs"
}
