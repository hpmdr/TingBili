package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.BiliSpaceVideo
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import coil3.compose.AsyncImage

@Composable
fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

fun formatSpaceDuration(sec: Long): String {
    if (sec <= 0) return "--:--"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun BiliNoAuthGate(title: String, onBack: () -> Unit = {}, onLogin: () -> Unit) {
    TingBiliScaffold(topBar = { TingBiliTopAppBar(title = title, navigationIcon = { BackNavIcon(onBack = onBack) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("未登录哔哩哔哩", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("登录后查看你的B站数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onLogin) { Text("去登录") }
            }
        }
    }
}

@Composable
fun BiliErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun BiliVideoRow(
    item: BiliSpaceVideo,
    onClick: () -> Unit,
) {
    val imageFormat = LocalImageFormat.current
    Card(
        onClick = onClick,
        enabled = item.playable,
        modifier = Modifier.fillMaxWidth().alpha(if (item.playable) 1f else 0.55f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 120.dp, height = 68.dp).clip(MaterialTheme.shapes.medium)) {
                AsyncImage(
                    model = item.cover.biliImage(BiliImageVariant.ListThumb, imageFormat),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        if (item.upper.isNotBlank()) append(item.upper)
                        append(" · ")
                        append(formatSpaceDuration(item.durationSec))
                        if (!item.playable && item.tag.isNotBlank()) append(" · ").append(item.tag)
                        if (!item.playable && item.tag.isBlank()) append(" · 非视频")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun BiliVideoList(
    list: List<BiliSpaceVideo>,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onItemClick: (BiliSpaceVideo) -> Unit,
    emptyText: String = "暂无内容",
) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留出悬浮 mini 的高度，最后一项不会被盖住
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            top = 12.dp,
            end = 12.dp,
            bottom = 12.dp + cn.debubu.tingbili.core.ui.MiniPlayerClearance
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { (it.bvid.ifBlank { it.title }) + it.cover }) { item ->
            BiliVideoRow(item = item, onClick = { if (item.playable) onItemClick(item) })
        }
        if (hasMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
                    else TextButton(onClick = onLoadMore) { Text("加载更多") }
                }
            }
        }
    }
}
