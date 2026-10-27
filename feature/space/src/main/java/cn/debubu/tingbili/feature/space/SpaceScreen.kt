package cn.debubu.tingbili.feature.space

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.core.ui.formatDurationMs
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.UpSpaceInfo
import cn.debubu.tingbili.data.bilibili.dto.UpVideo
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * UP 主主页：
 * 居中标题顶栏 → 紧凑资料头（头像叠 LV 徽章 + 竖线分隔数据）→ 吸顶排序/搜索栏 → 无分割线列表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpaceScreen(
    onBack: () -> Unit = {},
    onVideoClick: (String) -> Unit = {},
    vm: SpaceViewModel = hiltViewModel(),
) {
    val info by vm.info.collectAsStateWithLifecycle()
    val infoLoading by vm.infoLoading.collectAsStateWithLifecycle()
    val infoError by vm.infoError.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val searchKeyword by vm.searchKeyword.collectAsStateWithLifecycle()
    val paging = vm.pagingFlow.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "UP 详情",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item {
                SpaceHeader(
                    info = info,
                    loading = infoLoading,
                    error = infoError,
                    total = total,
                    onRetry = vm::retryInfo
                )
            }
            stickyHeader {
                SortSection(
                    current = order,
                    onSelect = { selected ->
                        vm.setOrder(selected)
                        scope.launch { listState.animateScrollToItem(1) }
                    },
                    searchOpen = searchOpen,
                    onOpenSearch = { searchOpen = true },
                    onSearch = { query ->
                        vm.setSearchKeyword(query)
                        scope.launch { listState.animateScrollToItem(1) }
                    },
                    onCloseSearch = {
                        searchOpen = false
                        vm.setSearchKeyword("")
                        scope.launch { listState.animateScrollToItem(1) }
                    }
                )
            }

            val refresh = paging.loadState.refresh
            val append = paging.loadState.append
            when {
                refresh is LoadState.Loading && paging.itemCount == 0 -> item {
                    StateBlock { CircularProgressIndicator(strokeWidth = 2.5.dp) }
                }
                refresh is LoadState.Error && paging.itemCount == 0 -> item {
                    StateBlock {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = refresh.error.message ?: "加载失败，请稍后重试",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = { paging.retry() }) { Text("重试") }
                    }
                }
                refresh is LoadState.NotLoading && paging.itemCount == 0 -> item {
                    StateBlock {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (searchKeyword.isBlank()) "TA 还没有投稿" else "没有找到相关投稿",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> items(
                    count = paging.itemCount,
                    key = { index -> paging.peek(index)?.let { "${it.bvid}#$index" } ?: "ph-$index" }
                ) { index ->
                    val video = paging[index]
                    if (video != null) {
                        UpVideoRow(video = video, onClick = { onVideoClick(video.bvid) })
                    }
                }
            }

            if (append is LoadState.Loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
            } else if (append is LoadState.Error) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { paging.retry() }) { Text("加载失败，点此重试") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceHeader(
    info: UpSpaceInfo?,
    loading: Boolean,
    error: String?,
    total: Int,
    onRetry: () -> Unit,
) {
    val imageFormat = LocalImageFormat.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(64.dp)) {
                AsyncImage(
                    model = info?.face?.takeIf { it.isNotBlank() }
                        ?.biliImage(BiliImageVariant.Avatar, imageFormat),
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        ),
                    contentScale = ContentScale.Crop
                )
                val level = info?.level ?: 0
                if (level > 0) {
                    LevelBadge(
                        level = level,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 2.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info?.name?.takeIf { it.isNotBlank() } ?: if (loading) "加载中…" else "UP 主",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!info?.officialTitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = info.officialTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (info != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatValue(value = formatCount(info.fans), label = "粉丝")
                        StatDivider()
                        StatValue(value = formatCount(info.following), label = "关注")
                        StatDivider()
                        StatValue(value = total.toString(), label = "投稿")
                    }
                }
            }
            if (error != null) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }

        if (info != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = info.sign.ifBlank { "这个人很神秘，什么都没写" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 生日/学校/职业：有值才展示；B 站未填生日时默认 "01-01"，按空处理
            val extras = listOfNotNull(
                info.birthday.takeIf { it.isNotBlank() && it != "01-01" },
                info.school.takeIf { it.isNotBlank() },
                info.profession.takeIf { it.isNotBlank() },
            )
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = extras.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "UP 主资料加载失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StatValue(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .width(1.dp)
            .height(12.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 头像右下角的等级徽章：主题色胶囊 + 底色描边 */
@Composable
private fun LevelBadge(level: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "LV$level",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/** 吸顶的排序/搜索栏：灰底分段控件 + 独立搜索按钮，搜索框在其下方展开 */
@Composable
private fun SortSection(
    current: SpaceOrder,
    onSelect: (SpaceOrder) -> Unit,
    searchOpen: Boolean,
    onOpenSearch: () -> Unit,
    onSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(4.dp)
            ) {
                SpaceOrder.entries.forEach { option ->
                    val selected = option == current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (selected) Modifier.background(MaterialTheme.colorScheme.surface)
                                else Modifier
                            )
                            .clickable { onSelect(option) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onOpenSearch),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "在 TA 的投稿里搜索",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (searchOpen) {
            SpaceSearchField(onSearch = onSearch, onClose = onCloseSearch)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** 展开的搜索框：灰底 12dp 圆角，左放大镜、右关闭 */
@Composable
private fun SpaceSearchField(onSearch: (String) -> Unit, onClose: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(input) }),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = "搜索该UP主的音频视频…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
        IconButton(
            onClick = {
                input = ""
                onClose()
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭搜索",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpVideoRow(video: UpVideo, onClick: () -> Unit) {
    val imageFormat = LocalImageFormat.current
    val cover = video.cover.takeIf { it.isNotBlank() }
        ?.biliImage(BiliImageVariant.ListThumb, imageFormat)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(modifier = Modifier.size(width = 112.dp, height = 72.dp)) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
            if (video.durationSec > 0) {
                Text(
                    text = formatDurationMs(video.durationSec * 1000L),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f).height(72.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetaItem(
                    icon = Icons.Outlined.PlayCircleOutline,
                    text = formatCount(video.play)
                )
                formatShortDate(video.pubdateSec)?.let { date ->
                    MetaItem(icon = Icons.Outlined.Schedule, text = date)
                }
            }
        }
    }
}

@Composable
private fun MetaItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1
        )
    }
}

@Composable
private fun StateBlock(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** 播放量/粉丝数：十万以内原样，以上用万/亿 */
private fun formatCount(n: Long): String = when {
    n >= 100_000_000L -> "%.1f亿".format(n / 100_000_000.0)
    n >= 10_000L -> "%.1f万".format(n / 10_000.0)
    else -> n.toString()
}

/** 列表里的短日期：MM-dd（与设计稿一致，不占宽度） */
private fun formatShortDate(epochSec: Long): String? {
    if (epochSec <= 0L) return null
    return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(epochSec * 1000L))
}
