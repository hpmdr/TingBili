package cn.debubu.tingbili.feature.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onTrackToDetail: (String) -> Unit = {},
    onUpClick: (Long) -> Unit = {},
    vm: HomeViewModel = hiltViewModel()
) {
    val paging = vm.pagingFlow.collectAsLazyPagingItems()
    val keyword by vm.keyword.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val snapshot by vm.snapshotTracks.collectAsStateWithLifecycle()
    val homeKeywords by vm.homeKeywords.collectAsStateWithLifecycle()
    val history by vm.searchHistory.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val snapshotGridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    // 不在编辑时，输入框显示当前列表的搜索词（列表就是这个词的结果）
    LaunchedEffect(keyword, inputFocused) {
        if (!inputFocused) input = keyword
    }
    // 快捷搜索（历史/推荐词）只在“正在编辑且输入为空”时出现；其余时刻始终是同一个列表。
    // 点 × 会重新聚焦输入框，所以这里能覆盖“清空后继续编辑”的场景。
    var showSuggestions by remember { mutableStateOf(false) }
    LaunchedEffect(input, inputFocused) {
        showSuggestions = inputFocused && input.isBlank()
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    fun doSearch(raw: String) {
        // 空输入沿用当前词，与搜索框里的提示一致
        val query = raw.trim().ifEmpty { keyword.ifBlank { homeKeywords.firstOrNull().orEmpty() } }
        if (query.isEmpty()) return
        input = query
        focusManager.clearFocus()
        keyboard?.hide()
        vm.search(query)
        scope.launch { gridState.scrollToItem(0) }
    }

    // 返回键：编辑中先退出编辑回列表；非编辑态保持默认（退出应用）
    BackHandler(enabled = inputFocused) {
        input = keyword
        focusManager.clearFocus()
        keyboard?.hide()
    }

    // SWR：Paging 尚无数据但有快照时，先秒开快照，后台刷新到了再切到 Paging。
    val showSnapshot = paging.itemCount == 0 && !snapshot.isNullOrEmpty()
    // 回顶只滚可见的那个：不可见的 Grid 虽未组合（不占内存），滚它白费一帧主线程
    val scrollTop: suspend () -> Unit = {
        if (showSnapshot) snapshotGridState.animateScrollToItem(0)
        else gridState.animateScrollToItem(0)
    }
    // 底栏双击搜索 tab 回顶（经总线发事件；标题双击走直连，两路都到这）
    val scrollTopTick by vm.scrollTopTick.collectAsStateWithLifecycle()
    LaunchedEffect(scrollTopTick) {
        if (scrollTopTick > 0) scrollTop()
    }

    // 下拉指示器只跟随用户主动刷新；初始加载走全屏转圈，不抢戏
    val refreshState = paging.loadState.refresh
    LaunchedEffect(refreshState) {
        if (refreshState !is LoadState.Loading) isRefreshing = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        HomeSearchEntry(
            input = input,
            onInputChange = { input = it },
            onFocusChange = { inputFocused = it },
            placeholder = if (keyword.isBlank()) {
                "搜索 B站视频 / 关键词"
            } else {
                "当前搜索：$keyword"
            },
            onSubmit = { doSearch(input) },
            // 清空只清输入框，不动列表；同时重新聚焦，直接进快捷搜索
            onClearInput = {
                input = ""
                searchFocus.requestFocus()
                keyboard?.show()
            },
            focusRequester = searchFocus,
            onTitleDoubleClick = { scope.launch { scrollTop() } },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
        if (showSuggestions) {
            SearchSuggestions(
                history = history,
                suggestKeywords = homeKeywords,
                onHistoryClick = { doSearch(it) },
                onHistoryRemove = vm::removeSearchHistory,
                onClearHistory = {
                    vm.clearSearchHistory()
                    Toast.makeText(context, "已清空搜索历史", Toast.LENGTH_SHORT).show()
                },
                onSuggestClick = { doSearch(it) },
                modifier = Modifier.weight(1f)
            )
        } else {
            if (total != null && paging.itemCount > 0) {
                Text(
                    text = "找到相关音频内容约 $total 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            // 和下拉圈圈互斥：用户主动下拉时只露圈圈（官方手势反馈），后台自动刷新时只露顶条；
            // 任何时刻只留一个 loading 样式
            if (!isRefreshing && paging.loadState.refresh is LoadState.Loading && (paging.itemCount > 0 || showSnapshot)) {
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
                    text = "上次内容 · 正在更新",
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
                    vm.refresh()
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
                        onUpClick = onUpClick,
                    )
                } else {
                    TrackPagingGrid(
                        paging = paging,
                        gridState = gridState,
                        onTrackClick = { track -> onTrackToDetail(track.bvid) },
                        onUpClick = onUpClick,
                        emptyText = "没有找到相关视频，换个关键词试试"
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotErrorBanner(
    message: String?,
    onRetry: () -> Unit
) {
    // 412 时连点重试只会加重限流，按钮冷却 3s 掐一下。
    var cooling by remember { mutableStateOf(false) }
    LaunchedEffect(cooling) {
        if (cooling) {
            delay(3000)
            cooling = false
        }
    }
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
            text = "更新失败：${friendlyHomeError(message)}，当前为上次内容",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                if (cooling) return@Button
                cooling = true
                onRetry()
            },
            enabled = !cooling
        ) { Text(if (cooling) "稍候…" else "重试") }
    }
}

@Composable
private fun SnapshotGrid(
    tracks: List<Track>,
    gridState: LazyGridState,
    onTrackClick: (Track) -> Unit,
    onUpClick: (Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(
            items = tracks,
            // 同一 BV 可能重复出现，缀上 index 兜底防 key 撞车
            key = { index, track -> "${track.bvid}:${track.cid}#$index" }
        ) { _, track ->
            TrackGridCard(
                track = track,
                onClick = { onTrackClick(track) },
                onUpClick = onUpClick,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeSearchEntry(
    input: String,
    onInputChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    onClearInput: () -> Unit,
    focusRequester: FocusRequester,
    onTitleDoubleClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "搜索",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onDoubleClick = onTitleDoubleClick,
            )
        )
        Spacer(Modifier.width(12.dp))
        // 用 BasicTextField 自绘外壳：M3 TextField 自带 56dp 最小高度 + 大内边距，
        // 在首页顶栏显得过胖；这里高度完全由内容决定（约 40dp），与原来的胶囊条一致
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (input.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                    if (input.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "清空",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onClearInput)
                                .padding(7.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

/** 搜索建议区：搜索历史 + 推荐关键词（两者都空时留白，等用户输入） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestions(
    history: List<String>,
    suggestKeywords: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSuggestClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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
                        SuggestionChip(onClick = { onSuggestClick(kw) }, label = { Text(kw) })
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
private fun TrackPagingGrid(
    paging: LazyPagingItems<Track>,
    gridState: LazyGridState,
    onTrackClick: (Track) -> Unit,
    onUpClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "暂无内容"
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
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
                    onClick = { onTrackClick(track) },
                    onUpClick = onUpClick,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PagingFooter(paging, emptyText)
        }
    }
}

@Composable
private fun PagingFooter(paging: LazyPagingItems<Track>, emptyText: String) {
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
                text = emptyText,
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
    // 同上：412 限流时防连点，3s 内只放行一次重试。
    var cooling by remember { mutableStateOf(false) }
    LaunchedEffect(cooling) {
        if (cooling) {
            delay(3000)
            cooling = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "加载失败：${friendlyHomeError(message)}",
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (cooling) return@Button
                cooling = true
                onRetry()
            },
            enabled = !cooling
        ) { Text(if (cooling) "稍候…" else "重试") }
    }
}

/** 412/风控文案转义：B 站原始 message 又长又吓人，转成一句话。 */
private fun friendlyHomeError(raw: String?): String {
    if (raw == null) return "请稍后重试"
    if (raw.contains("412") || raw.contains("频繁") || raw.contains("拦截") || raw.contains("限流")) {
        return "请求太频繁，B站限流了，稍等几秒再试"
    }
    return raw
}

@Composable
private fun TrackGridCard(
    track: Track,
    onClick: () -> Unit,
    onUpClick: (Long) -> Unit = {}
) {
    val imageFormat = LocalImageFormat.current
    // 网格列宽 156dp 按 3x 取 ListThumb 刚好；URL/时长/副标题缓存，不随父重组重复拼串
    val coverUrl = remember(track.cover, imageFormat) {
        track.cover.biliImage(BiliImageVariant.ListThumb, imageFormat)
    }
    val durationText = remember(track.durationMs) {
        if (track.durationMs > 0L) formatDuration(track.durationMs) else null
    }
    // 分P数只在详情/快照数据里有；搜索接口不返回，不值得每张卡片再查一次，故卡片不展示
    // UP 名单独成一行且可点击进主页；mid 缺失（旧快照/接口没返回）时退化为普通文本
    val upMid = track.upMid?.takeIf { it > 0 }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            if (durationText != null) {
                Text(
                    text = durationText,
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
            // 固定两行高度：标题一行/两行时下面的 UP 行都在同一水平线，网格才不会一行高一行的低
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (track.author.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "UP: ${track.author}",
                style = MaterialTheme.typography.bodySmall,
                // 能进主页时用主题色，明确"这行可点"
                color = if (upMid != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (upMid != null) {
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        // 子元素自己消费点击，不会冒泡到卡片（不会误进视频详情）
                        .clickable { onUpClick(upMid) }
                        .padding(vertical = 2.dp, horizontal = 2.dp)
                } else {
                    Modifier
                }
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
