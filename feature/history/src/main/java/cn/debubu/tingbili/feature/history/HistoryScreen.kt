package cn.debubu.tingbili.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.model.episodePageLabel
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.playedProgressLabel
import cn.debubu.tingbili.core.ui.component.MessageSnackbarEffect
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliSnackbarHost
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@Composable
fun HistoryScreen(
    onFolderClick: (Long) -> Unit = {},
    vm: HistoryViewModel = hiltViewModel(),
) {
    val list by vm.history.collectAsStateWithLifecycle()
    val info by vm.videoInfo.collectAsStateWithLifecycle()
    val folderIds by vm.folderIds.collectAsStateWithLifecycle()
    val startingResume by vm.startingResume.collectAsStateWithLifecycle()
    val pendingKey by vm.pendingKey.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    MessageSnackbarEffect(message, snackbarHostState, vm::consumeMessage)

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "本地历史记录",
                subtitle = if (list.isNotEmpty()) "共 ${list.size} 条播放记录" else null,
                actions = {
                    if (list.isNotEmpty()) {
                        TextButton(onClick = { vm.clearAll() }) { Text("清空") }
                    }
                }
            )
        },
        snackbarHost = { TingBiliSnackbarHost(snackbarHostState) },
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
                items(list, key = { "${it.sourceType}:${it.sourceId}" }) { item ->
                    // 可见才拉：行进入组合才发 view 请求，进页不再 N 路齐发
                    LaunchedEffect(item.bvid) { vm.prefetch(item.bvid) }
                    val track = info[item.bvid].orEmpty().firstOrNull { it.cid == item.cid }
                    val isFolder = item.sourceType == HistoryEntity.SOURCE_FOLDER
                    val folderId = item.sourceId.toLongOrNull()
                    val folderExists = isFolder && folderId != null &&
                        (folderIds?.contains(folderId) ?: true)
                    HistoryRow(
                        entity = item,
                        track = track,
                        isFolder = isFolder,
                        folderExists = folderExists,
                        isPending = startingResume &&
                            pendingKey == "${item.sourceType}:${item.sourceId}",
                        onResume = { vm.resume(item) },
                        onFolderClick = { folderId?.let(onFolderClick) },
                        onDelete = { vm.delete(item) }
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
    isFolder: Boolean,
    folderExists: Boolean,
    isPending: Boolean = false,
    onResume: () -> Unit,
    onFolderClick: () -> Unit,
    onDelete: () -> Unit
) {
    val imageFormat = LocalImageFormat.current
    val bvTitle = track?.videoTitle?.takeIf { it.isNotBlank() }
        ?: track?.title?.takeIf { it.isNotBlank() }
        ?: entity.sourceTitle?.takeIf { it.isNotBlank() }
        ?: entity.bvid
    // 收藏夹记录：主标题是夹名，副标题才是 BV 标题；BV 记录直接用 BV 标题
    val mainTitle = if (isFolder) {
        entity.sourceTitle?.takeIf { it.isNotBlank() } ?: "收藏夹"
    } else {
        bvTitle
    }
    val folderSubtitle = if (isFolder && bvTitle != mainTitle) bvTitle else null

    // 行内字符串拼装（trim/startsWith/SimpleDateFormat）每帧都贵，key 变化才重算
    val chapterText = remember(track, bvTitle) { historyChapterLine(track, bvTitle) }
    val coverUrl = remember(track?.cover, imageFormat) {
        track?.cover?.takeIf { it.isNotBlank() }?.biliImage(BiliImageVariant.ListThumb, imageFormat)
    }
    val dateText = remember(entity.updatedAt) { formatDate(entity.updatedAt) }
    val progressText = remember(track?.pageIndex, entity.positionMs, track?.durationMs, track?.videoTitle) {
        // 单 P 不显示 P1，与播放页/迷你/通知栏统一
        val page = track?.let { episodePageLabel(it.title, it.videoTitle, it.pageIndex) }
        "上次听到 " + playedProgressLabel(
            pageIndex = if (page != null) track?.pageIndex else null,
            positionMs = entity.positionMs,
            durationMs = track?.durationMs,
        )
    }

    Card(
        onClick = onResume,
        enabled = !isPending,
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
                model = coverUrl,
                contentDescription = null,
                // 正方封面：横/方/竖源都只居中裁掉边缘，主体不断头；
                // 96dp 与右侧文字区等高，不再显小
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mainTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    SourceBadge(
                        isFolder = isFolder,
                        folderExists = folderExists,
                        onClick = onFolderClick,
                    )
                }
                folderSubtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
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
                        text = dateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                track?.durationMs?.takeIf { it > 0 }?.let { duration ->
                    Spacer(Modifier.height(6.dp))
                    val fraction = (entity.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        // 细线上末端圆点像脏点，去掉（与 mini 条一致）
                        drawStopIndicator = {},
                    )
                }
            }
            if (isPending) {
                // 续播要两跳网络等待，就地给反馈并禁掉重复点击
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else {
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
}

/**
 * 类型 badge：收藏夹可点跳转（`收藏夹 ›`），删除后置灰；
 * BV 只做类型标识。中性色，不使用主题强调色。
 */
@Composable
private fun SourceBadge(
    isFolder: Boolean,
    folderExists: Boolean,
    onClick: () -> Unit,
) {
    val text = when {
        !isFolder -> "BV"
        folderExists -> "收藏夹 ›"
        else -> "收藏夹已删除"
    }
    val enabled = isFolder && folderExists
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled || !isFolder) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
        val page = episodePageLabel(track.title, track.videoTitle, track.pageIndex)
        if (page != null) {
            add(if (keepName) "$page · $short" else page)
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
