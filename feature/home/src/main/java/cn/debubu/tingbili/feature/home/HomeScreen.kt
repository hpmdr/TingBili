package cn.debubu.tingbili.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@Composable
fun HomeScreen(
    onTrackToDetail: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel()
) {
    val paging = vm.pagingFlow.collectAsLazyPagingItems()
    val homeKeywords by vm.homeKeywords.collectAsStateWithLifecycle()
    val snapshot by vm.snapshotTracks.collectAsStateWithLifecycle()
    val pagingStarted by vm.pagingStarted.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val snapshotGridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    // 下拉指示器只跟随用户主动刷新；初始加载走全屏转圈，不抢戏
    val refreshState = paging.loadState.refresh
    LaunchedEffect(refreshState) {
        if (refreshState !is LoadState.Loading) isRefreshing = false
    }

    // SWR：Paging 尚无数据但有快照时，先秒开快照，后台刷新到了再切到 Paging。
    val showSnapshot = paging.itemCount == 0 && !snapshot.isNullOrEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        HomeSearchEntry(
            onClick = onSearchClick,
            keywords = homeKeywords,
            // 双击标题回顶部：下拉刷新处同款（两个 grid 哪个可见回哪个）
            onTitleDoubleClick = {
                scope.launch {
                    gridState.animateScrollToItem(0)
                    snapshotGridState.animateScrollToItem(0)
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
        if (paging.loadState.refresh is LoadState.Loading && (paging.itemCount > 0 || showSnapshot)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        // 有快照兜底时，刷新失败不弹整屏错误，只露横幅（保列表）；无快照才走 Paging 整屏态。
        if (showSnapshot && refreshState is LoadState.Error) {
            SnapshotErrorBanner(
                message = refreshState.error.message,
                onRetry = { paging.retry() }
            )
        }
        if (showSnapshot && refreshState is LoadState.Loading) {
            Text(
                text = "上次推荐 · 正在更新",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (showSnapshot && !pagingStarted) {
            Text(
                text = "上次推荐 · 下拉获取新内容",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                vm.shuffleKeywords()
                scope.launch {
                    gridState.scrollToItem(0)
                    snapshotGridState.scrollToItem(0)
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (showSnapshot) {
                SnapshotGrid(
                    tracks = snapshot!!,
                    gridState = snapshotGridState,
                    onTrackClick = { track -> onTrackToDetail(track.bvid) },
                    onLoadMore = vm::startPaging,
                )
            } else {
                TrackPagingGrid(
                    paging = paging,
                    gridState = gridState,
                    onTrackClick = { track -> onTrackToDetail(track.bvid) }
                )
            }
        }
    }
}

@Composable
private fun SnapshotErrorBanner(
    message: String?,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "更新失败：${message ?: "请稍后重试"}，当前为上次推荐",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun SnapshotGrid(
    tracks: List<Track>,
    gridState: LazyGridState,
    onTrackClick: (Track) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = tracks,
            key = { "${it.bvid}:${it.cid}" }
        ) { track ->
            TrackGridCard(
                track = track,
                onClick = { onTrackClick(track) }
            )
        }
        // 快照只有首屏：到底不是没数据，是 Paging 还没开闸，按钮切过去继续翻
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "快照只有首屏，下拉或点下面继续看最新推荐",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onLoadMore) { Text("加载更多推荐") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSearchEntry(
    onClick: () -> Unit,
    keywords: List<String>,
    onTitleDoubleClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "听视频",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onDoubleClick = onTitleDoubleClick,
            )
        )
        Spacer(Modifier.width(12.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "搜索",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (keywords.isEmpty()) "搜索 B站视频 / 关键词"
                else "搜索 B站视频 · 推荐：${keywords.joinToString("/")}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrackPagingGrid(
    paging: LazyPagingItems<Track>,
    gridState: LazyGridState,
    onTrackClick: (Track) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            count = paging.itemCount,
            // key 必须全局唯一：B 站搜索常返回重复 BV（cid=0 的未解析项），
            // 同一 BV 在一页出现两次时 "bvid:cid" 会撞车闪退，故缀上 index 兜底。
            key = { index ->
                paging.peek(index)?.let { "${it.bvid}:${it.cid}#$index" } ?: "placeholder-$index"
            }
        ) { index ->
            val track = paging[index]
            if (track != null) {
                TrackGridCard(
                    track = track,
                    onClick = { onTrackClick(track) }
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PagingFooter(paging)
        }
    }
}

@Composable
private fun PagingFooter(paging: LazyPagingItems<Track>) {
    val refreshState = paging.loadState.refresh
    val appendState = paging.loadState.append

    when {
        refreshState is LoadState.Loading && paging.itemCount == 0 -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        appendState is LoadState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        refreshState is LoadState.Error -> {
            PagingError(
                message = refreshState.error.message,
                onRetry = { paging.retry() }
            )
        }
        appendState is LoadState.Error -> {
            PagingError(
                message = appendState.error.message,
                onRetry = { paging.retry() }
            )
        }
        refreshState is LoadState.NotLoading && paging.itemCount == 0 -> {
            Text(
                text = "暂无推荐内容",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PagingError(
    message: String?,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "加载失败：${message ?: "请稍后重试"}",
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun TrackGridCard(
    track: Track,
    onClick: () -> Unit
) {
    val imageFormat = LocalImageFormat.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box {
            AsyncImage(
                model = track.cover.biliImage(BiliImageVariant.ListThumb, imageFormat),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            if (track.durationMs > 0L) {
                Text(
                    text = formatDuration(track.durationMs),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                if (track.author.isNotBlank()) append("UP: ${track.author}")
                if (track.pageCount > 1) {
                    if (isNotEmpty()) append(" · ")
                    append("${track.pageCount}P")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
