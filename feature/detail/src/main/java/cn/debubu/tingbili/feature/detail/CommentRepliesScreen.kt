package cn.debubu.tingbili.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 楼中楼详情页（抄 PiliPlus VideoReplyReplyPanel 的结构，精简成独立页）。
 *
 * 结构对位：楼主头部（不挂预览盒）→ 粗分割线 → 「相关回复共 X 条」→ 子评论列表 → 底部翻页。
 * PiliPlus 还有排序切换 / 查看对话 / 对话列表，REST 语义不明 + 只读，先不抄。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentRepliesScreen(
    onBack: () -> Unit = {},
    viewModel: CommentRepliesViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "评论详情",
                subtitle = viewModel.videoTitle.takeIf { it.isNotBlank() },
                navigationIcon = { BackNavIcon(onBack = onBack) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val error = state.error
            val root = state.root
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> CommentErrorBlock(
                    message = error,
                    onRetry = viewModel::reload,
                    modifier = Modifier.align(Alignment.Center),
                )
                root == null -> Text(
                    text = "评论可能已被删除",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> RepliesList(
                    state = state,
                    onLoadMore = viewModel::loadMore,
                    onRetryLoadMore = viewModel::retryLoadMore,
                )
            }
        }
    }
}

@Composable
private fun RepliesList(
    state: CommentRepliesUiState,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
) {
    val root = state.root ?: return
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
        // 楼主头部：全文 + 点赞数，不挂预览盒（对 PiliPlus _header）
        item(key = "replies-root-${root.rpid}") {
            CommentRow(comment = root, upMid = state.upMid, showPreview = false)
        }
        item(key = "replies-divider") {
            HorizontalDivider(
                thickness = 6.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
        }
        // 相关回复计数（对 PiliPlus _sortWidget；排序切换先不抄）
        item(key = "replies-count") {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = if (state.total > 0) {
                        "相关回复共 ${formatCommentCount(state.total.toLong())} 条"
                    } else {
                        "相关回复"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(items = state.replies, key = { "reply-${it.rpid}" }) { reply ->
            CommentRow(comment = reply, upMid = state.upMid, showPreview = false)
            HorizontalDivider(
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
        item(key = "replies-footer") {
            CommentFooter(
                loadingMore = state.loadingMore,
                appendError = state.appendError,
                end = state.end,
                onRetry = onRetryLoadMore,
            )
        }
    }
}
