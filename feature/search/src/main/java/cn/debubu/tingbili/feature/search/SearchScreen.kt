package cn.debubu.tingbili.feature.search

import android.content.Context
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@Composable
fun SearchScreen(
    onBack: () -> Unit = {},
    onTrackToDetail: (String) -> Unit = {},
    vm: SearchViewModel = hiltViewModel()
) {
    val paging = vm.pagingFlow.collectAsLazyPagingItems()
    val kw by vm.keyword.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf(kw) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    val triggerSearch: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        vm.onSearch(input)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("搜索 B站视频 / 关键词") },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { triggerSearch() })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = triggerSearch) { Text("搜索") }
        }
        Spacer(Modifier.height(12.dp))
        if (paging.loadState.refresh is LoadState.Loading && paging.itemCount > 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        SearchResultList(
            paging = paging,
            showEmptyHint = kw.isBlank(),
            onTrackClick = { onTrackToDetail(it.bvid) }
        )
    }
}

@Composable
private fun SearchResultList(
    paging: LazyPagingItems<Track>,
    showEmptyHint: Boolean,
    onTrackClick: (Track) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = paging.itemCount) { index ->
            val track = paging[index]
            if (track != null) {
                SearchRow(track = track, onClick = { onTrackClick(track) })
            }
        }
        item {
            SearchFooter(
                paging = paging,
                showEmptyHint = showEmptyHint
            )
        }
    }
}

@Composable
private fun SearchFooter(
    paging: LazyPagingItems<Track>,
    showEmptyHint: Boolean
) {
    val refreshState = paging.loadState.refresh
    val appendState = paging.loadState.append

    when {
        refreshState is LoadState.Loading && paging.itemCount == 0 -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        appendState is LoadState.Loading -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        refreshState is LoadState.Error -> {
            SearchError(
                message = refreshState.error.message,
                onRetry = { paging.retry() }
            )
        }
        appendState is LoadState.Error -> {
            SearchError(
                message = appendState.error.message,
                onRetry = { paging.retry() }
            )
        }
        refreshState is LoadState.NotLoading && paging.itemCount == 0 -> {
            Text(
                text = if (showEmptyHint) "输入关键词搜索 B站视频" else "没有找到相关视频",
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchError(
    message: String?,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
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
private fun SearchRow(track: Track, onClick: () -> Unit) {
    val imageFormat = LocalImageFormat.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp)
    ) {
        Box {
            AsyncImage(
                model = track.cover.biliImage(BiliImageVariant.ListThumb, imageFormat),
                contentDescription = null,
                modifier = Modifier.width(116.dp).height(72.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            if (track.durationMs > 0L) {
                Text(
                    text = formatDuration(track.durationMs),
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).height(72.dp), verticalArrangement = Arrangement.SpaceBetween) {
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
