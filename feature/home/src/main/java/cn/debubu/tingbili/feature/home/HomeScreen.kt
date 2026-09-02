package cn.debubu.tingbili.feature.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import cn.debubu.tingbili.core.data.model.Track
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel = hiltViewModel()
) {
    val paging = vm.pagingFlow.collectAsLazyPagingItems()
    val kw by vm.keyword.collectAsState()
    val showSheet by vm.showBottomSheet.collectAsState()
    val expanded by vm.expandedTracks.collectAsState()
    val isLoadingBv by vm.isLoadingBv.collectAsState()
    val bvError by vm.bvError.collectAsState()
    val selectedBvid by vm.selectedBvid.collectAsState()

    var input by remember { mutableStateOf(kw) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("搜索 B站视频 / 关键词") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.onSearch(input) }) { Text("搜索") }
        }
        Spacer(Modifier.height(12.dp))
        TrackPagingList(
            paging = paging,
            onTrackClick = { track ->
                // Expand BV bottomSheet for any track; track from search has cid=0 so must call view()
                vm.showBottomSheetForBv(track.bvid)
            },
            onTrackPlayDirect = { track -> vm.playSingle(track) }
        )
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { vm.dismissBottomSheet() },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = "BV: ${selectedBvid ?: ""}", modifier = Modifier.padding(bottom = 8.dp))
                if (isLoadingBv) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (bvError != null) {
                    Text(text = "加载失败: $bvError")
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { selectedBvid?.let { vm.showBottomSheetForBv(it) } }) { Text("重试") }
                } else if (expanded.isNullOrEmpty()) {
                    Text("暂无分P")
                } else {
                    val tracks = expanded!!
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.playTracks(tracks, 0) }) { Text("整集合集直接播放") }
                        OutlinedButton(onClick = { vm.addToPlaylist(tracks) }) { Text("整集合集加入歌单") }
                    }
                    Spacer(Modifier.height(12.dp))
                    tracks.forEachIndexed { idx, t ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = t.title)
                                Text(text = "P${idx + 1} · ${t.author} · ${formatDuration(t.durationMs)}")
                            }
                            TextButton(onClick = { vm.playTracks(tracks, idx) }) { Text("播放") }
                            TextButton(onClick = { vm.playSingle(t) }) { Text("单P播放") }
                            TextButton(onClick = { vm.addToPlaylist(t) }) { Text("单P加入") }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TrackPagingList(
    paging: LazyPagingItems<Track>,
    onTrackClick: (Track) -> Unit,
    onTrackPlayDirect: (Track) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = paging.itemCount) { index ->
            val track = paging[index]
            if (track != null) {
                TrackRow(
                    track = track,
                    onClick = { onTrackClick(track) },
                    onPlay = { onTrackPlayDirect(track) }
                )
            }
        }
        paging.apply {
            when {
                loadState.refresh is LoadState.Loading -> {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
                loadState.append is LoadState.Loading -> {
                    item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                }
                loadState.refresh is LoadState.Error -> {
                    val e = (loadState.refresh as LoadState.Error).error
                    item { Text("加载失败: ${e.message}", modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onPlay: () -> Unit = onClick
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.cover,
            contentDescription = null,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = track.title, maxLines = 2)
            Text(text = track.author, maxLines = 1)
            Text(text = formatDuration(track.durationMs))
        }
        TextButton(onClick = onPlay) { Text("播放") }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
