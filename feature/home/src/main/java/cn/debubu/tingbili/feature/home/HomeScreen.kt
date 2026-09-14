package cn.debubu.tingbili.feature.home

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
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
    val kw by vm.keyword.collectAsState()

    var input by remember { mutableStateOf(kw) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    // 统一搜索入口：收起输入法、清除焦点后再执行搜索
    val triggerSearch: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        // 部分中文输入法会忽略 compose 的 hide()，再用 InputMethodManager 兜底
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        vm.onSearch(input)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        // 搜索入口（假输入框）：点击进入独立搜索页
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSearchClick() }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text("搜索 B站视频 / 关键词", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSearchClick) { Text("搜索") }
        }
        Spacer(Modifier.height(12.dp))
        // 已有结果时重新搜索：刷新期间在顶部显示进度条（旧结果保留，新数据到达后替换）。
        // 仅在 itemCount > 0 时显示：旋转/切页回来时 LazyPagingItems 初始 loadState 也是 Loading，
        // 但缓存回放 itemCount 尚为 0，此时不应显示误导性的加载动画
        if (paging.loadState.refresh is LoadState.Loading && paging.itemCount > 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        TrackPagingList(
            paging = paging,
            onTrackClick = { track ->
                // 点击搜索结果跳转视频详情页
                onTrackToDetail(track.bvid)
            }
        )
    }
}

@Composable
private fun TrackPagingList(
    paging: LazyPagingItems<Track>,
    onTrackClick: (Track) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = paging.itemCount) { index ->
            val track = paging[index]
            if (track != null) {
                TrackRow(
                    track = track,
                    onClick = { onTrackClick(track) }
                )
            }
        }
        paging.apply {
            when {
                // 首次加载（列表为空）才在列表中央转圈；有内容时的刷新由顶部进度条表示，避免两个动画同时出现
                loadState.refresh is LoadState.Loading && paging.itemCount == 0 -> {
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
    onClick: () -> Unit
) {
    val imageFormat = LocalImageFormat.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box {
            AsyncImage(
                model = track.cover.biliImage(BiliImageVariant.ListThumb, imageFormat),
                contentDescription = null,
                modifier = Modifier
                    .width(116.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            // 左下角时长角标
            if (track.durationMs > 0L) {
                Text(
                    text = formatDuration(track.durationMs),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 与封面等高：标题顶部对齐、UP 底部对齐，长短不一也整齐
        Column(
            modifier = Modifier.weight(1f).height(72.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (track.author.isNotBlank()) append("UP: ${track.author}")
                    if (track.pageCount > 1) {
                        if (isNotEmpty()) append(" · ")
                        append("${track.pageCount}P")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
