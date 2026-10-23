package cn.debubu.tingbili.feature.search

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    val defaultKw by vm.defaultKeyword.collectAsStateWithLifecycle()
    val history by vm.searchHistory.collectAsStateWithLifecycle()
    val suggestKeywords by vm.suggestKeywords.collectAsStateWithLifecycle()
    val totalCount by vm.totalCount.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf(kw) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    // 同一条目只自动聚焦一次：去详情页时本组合被销毁，返回是重建，
    // LaunchedEffect(Unit) 会重跑；用随条目保存的标记防止回来抢焦点弹键盘。
    // rememberSaveable 跟 NavBackStackEntry 走：返回是同一条目（不聚焦），
    // 从首页重新进是新条目（正常聚焦）。
    var focusedOnce by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!focusedOnce) {
            focusedOnce = true
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    fun doSearch(query: String) {
        focusManager.clearFocus()
        keyboard?.hide()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
        input = query
        vm.onSearch(query)
    }

    val triggerSearch: () -> Unit = {
        // 空输入时按首页推荐默认词搜，并回填输入框，结果与所见一致
        val query = input.ifBlank { defaultKw }
        if (query.isNotBlank()) doSearch(query)
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SearchBarRow(
            input = input,
            onInputChange = { input = it },
            placeholder = if (defaultKw.isBlank()) "搜索 B站视频 / 关键词" else "搜：${defaultKw}…",
            onBack = onBack,
            onSearch = triggerSearch,
            focusRequester = focusRequester,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        if (kw.isBlank()) {
            SearchGuide(
                history = history,
                suggestKeywords = suggestKeywords,
                onHistoryClick = { doSearch(it) },
                onHistoryRemove = { vm.removeHistory(it) },
                onClearHistory = {
                    vm.clearHistory()
                    Toast.makeText(context, "已清空搜索历史", Toast.LENGTH_SHORT).show()
                },
                onSuggestClick = { doSearch(it) }
            )
        } else {
            if (totalCount != null && paging.itemCount > 0) {
                Text(
                    text = "找到相关音频内容约 $totalCount 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            SearchResultBody(
                paging = paging,
                onTrackClick = { onTrackToDetail(it.bvid) }
            )
        }
    }
}

/** 顶行：返回键 + 与首页同风格的胶囊搜索条（无下划线，回车/搜索键触发） */
@Composable
private fun SearchBarRow(
    input: String,
    onInputChange: (String) -> Unit,
    placeholder: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        TextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (input.isNotEmpty()) {
                    IconButton(onClick = { onInputChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )
        Spacer(Modifier.width(4.dp))
    }
}

/** 空态：搜索历史 + 推荐搜索（无历史时整区分区隐藏） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchGuide(
    history: List<String>,
    suggestKeywords: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSuggestClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (history.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "搜索历史",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onClearHistory) { Text("清空") }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    history.forEach { h ->
                        HistoryChip(
                            text = h,
                            onClick = { onHistoryClick(h) },
                            onRemove = { onHistoryRemove(h) }
                        )
                    }
                }
            }
        }
        if (suggestKeywords.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "推荐搜索",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestKeywords.forEach { kw ->
                        SuggestionChip(
                            onClick = { onSuggestClick(kw) },
                            label = { Text(kw) }
                        )
                    }
                }
            }
        }
    }
}

/** 历史 chip：surfaceVariant 实底 + 历史图标 + 单条删除 */
@Composable
private fun HistoryChip(
    text: String,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "删除 $text",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchResultBody(
    paging: LazyPagingItems<Track>,
    onTrackClick: (Track) -> Unit
) {
    val refreshState = paging.loadState.refresh
    val appendState = paging.loadState.append

    when {
        refreshState is LoadState.Loading && paging.itemCount == 0 -> {
            CenterState {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "正在检索 B 站音频内容…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        refreshState is LoadState.Error && paging.itemCount == 0 -> {
            CenterState {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "加载失败：${refreshState.error.message ?: "请稍后重试"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { paging.retry() }) { Text("重试") }
            }
        }
        refreshState is LoadState.NotLoading && paging.itemCount == 0 -> {
            CenterState {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "没有找到相关视频",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "请尝试更换关键词或检查输入是否有误",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> {
            if (paging.itemCount > 0 && refreshState is LoadState.Loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    count = paging.itemCount,
                    key = { index -> paging.peek(index)?.let { "${it.bvid}:${it.cid}" } ?: "placeholder-$index" }
                ) { index ->
                    val track = paging[index]
                    if (track != null) {
                        SearchRow(track = track, onClick = { onTrackClick(track) })
                    }
                }
                if (appendState is LoadState.Loading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
                if (appendState is LoadState.Error) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "加载失败：${appendState.error.message ?: "请稍后重试"}",
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { paging.retry() }) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterState(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
private fun SearchRow(track: Track, onClick: () -> Unit) {
    val imageFormat = LocalImageFormat.current
    // 行图 116x72 按 2x 取 232x144，ListThumb 480 解码浪费 4 倍；URL 与时长文案缓存，不随父重组重复算
    val coverUrl = remember(track.cover, imageFormat) {
        track.cover.biliImage(232, 144, imageFormat)
    }
    val durationText = remember(track.durationMs) {
        if (track.durationMs > 0L) formatDuration(track.durationMs) else null
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp)
    ) {
        Box {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier.width(116.dp).height(72.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            if (durationText != null) {
                Text(
                    text = durationText,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
