package cn.debubu.tingbili.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.MiniPlayerClearance
import cn.debubu.tingbili.core.ui.component.BackNavIcon
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.model.BiliComment
import cn.debubu.tingbili.data.bilibili.model.CommentSort
import kotlinx.coroutines.flow.distinctUntilChanged

/** 详情页 → 评论页要带的信息：aid 是评论接口的 oid，标题/UP 只用于评论页头部与 UP 标 */
data class CommentTarget(
    val bvid: String,
    val aid: Long,
    val title: String,
    val upMid: Long,
)

/**
 * BV 评论页（详情页右上角「…」→ 查看 BV 评论）。
 *
 * 只读：展示根评论 + 楼中楼预览，支持热门/最新切换与游标翻页；
 * 点预览盒/回复数进楼中楼详情（对 PiliPlus replyReply  bottomSheet，精简成独立页）。
 * 不做点赞/回复（需要 csrf + 登录态，等真要做再补）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentScreen(
    onBack: () -> Unit = {},
    onOpenReplies: (aid: Long, title: String, upMid: Long, root: BiliComment) -> Unit = { _, _, _, _ -> },
    viewModel: CommentViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "评论",
                // 评论接口不带视频标题：详情页带过来，长标题靠 TopAppBar 自己省略
                subtitle = viewModel.videoTitle.takeIf { it.isNotBlank() },
                navigationIcon = { BackNavIcon(onBack = onBack) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CommentSortBar(
                sort = state.sort,
                total = state.total,
                onSort = viewModel::setSort,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                val error = state.error
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> CommentErrorBlock(
                        message = error,
                        onRetry = viewModel::reload,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    state.comments.isEmpty() -> Text(
                        text = "暂无评论",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> CommentList(
                        state = state,
                        onLoadMore = viewModel::loadMore,
                        onRetryLoadMore = viewModel::retryLoadMore,
                        onOpenReplies = { root ->
                            onOpenReplies(state.aid, viewModel.videoTitle, state.upMid, root)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentSortBar(
    sort: CommentSort,
    total: Int,
    onSort: (CommentSort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (total > 0) "共 ${formatCommentCount(total.toLong())} 条评论" else "评论",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = sort == CommentSort.HOT,
            onClick = { onSort(CommentSort.HOT) },
            label = { Text("热门") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = sort == CommentSort.LATEST,
            onClick = { onSort(CommentSort.LATEST) },
            label = { Text("最新") },
        )
    }
}

@Composable
private fun CommentList(
    state: CommentUiState,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onOpenReplies: (BiliComment) -> Unit,
) {
    val listState = rememberLazyListState()
    // 触底前 3 个列表项就预取，滚动不会明显顿一下
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onLoadMore() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 16.dp + MiniPlayerClearance,
        ),
    ) {
        items(items = state.comments, key = { it.rpid }) { comment ->
            CommentRow(
                comment = comment,
                upMid = state.upMid,
                onOpenReplies = onOpenReplies,
            )
            HorizontalDivider(
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
        item(key = "comment-footer") {
            CommentFooter(
                loadingMore = state.loadingMore,
                appendError = state.appendError,
                end = state.end,
                onRetry = onRetryLoadMore,
            )
        }
    }
}
