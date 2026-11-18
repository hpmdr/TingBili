package cn.debubu.tingbili.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.model.BiliComment
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentRepliesUiState(
    val root: BiliComment? = null,
    val replies: List<BiliComment> = emptyList(),
    val total: Int = 0,
    val upMid: Long = 0,
    /** 首屏中：整页转圈 */
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val end: Boolean = false,
    /** 首屏失败（整页错误 + 重试） */
    val error: String? = null,
    /** 加载更多失败：列表保留，底部给重试 */
    val appendError: String? = null,
)

/**
 * 楼中楼详情（抄 PiliPlus VideoReplyReplyPanel + VideoReplyReplyController）。
 *
 * 只调 REST `/x/v2/reply/reply`（pn 翻页，游客可读）：首屏拿 root + 第一页，
 * 上拉按 pn 追加。排序固定接口默认，不做切换（REST 的 sort 语义不明，
 * PiliPlus 登录态走 gRPC mode，游客 REST 也只传 sort=1）。
 *
 * 只读：不做回复/点赞（要 csrf + 登录态）。
 */
@HiltViewModel
class CommentRepliesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository,
) : ViewModel() {

    private val aid: Long = savedStateHandle["aid"] ?: 0L
    private val rootRpid: Long = savedStateHandle["root"] ?: 0L

    /** 副标题用：评论接口不带视频标题，从主列表带过来 */
    val videoTitle: String = savedStateHandle["title"] ?: ""

    private val routeUpMid: Long = savedStateHandle["upMid"] ?: 0L

    private val _ui = MutableStateFlow(CommentRepliesUiState())
    val ui: StateFlow<CommentRepliesUiState> = _ui.asStateFlow()

    private var pageNum = 1
    private var generation = 0
    private var loadJob: Job? = null

    init {
        reload()
    }

    /** 首屏 / 重试：pn=1 从头拉。in-flight 请求靠 [generation] 丢弃过期结果。 */
    fun reload() {
        loadJob?.cancel()
        val gen = ++generation
        pageNum = 1
        _ui.update {
            it.copy(
                root = null,
                replies = emptyList(),
                loading = true,
                loadingMore = false,
                end = false,
                error = null,
                appendError = null,
            )
        }
        loadJob = viewModelScope.launch {
            if (aid <= 0L || rootRpid <= 0L) {
                if (gen == generation) _ui.update { it.copy(loading = false, error = "评论不存在或已被删除") }
                return@launch
            }
            when (val result = repo.getCommentReplies(aid, rootRpid, pn = 1)) {
                is Result.Success -> if (gen == generation) {
                    val page = result.data
                    pageNum = page.pageNum
                    _ui.update {
                        it.copy(
                            root = page.root,
                            replies = page.replies,
                            total = page.total,
                            upMid = routeUpMid.takeIf { mid -> mid > 0 } ?: page.upMid,
                            loading = false,
                            end = page.isEnd,
                        )
                    }
                }
                is Result.Error -> if (gen == generation) {
                    _ui.update { it.copy(loading = false, error = result.msg.ifBlank { "评论加载失败" }) }
                }
            }
        }
    }

    /** 上拉触底：同一时刻只允许一个追加请求，到底/首屏未完成时直接忽略 */
    fun loadMore() {
        val state = _ui.value
        if (state.loading || state.loadingMore || state.end || state.appendError != null) return
        val gen = generation
        val next = pageNum + 1
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = repo.getCommentReplies(aid, rootRpid, pn = next)) {
                is Result.Success -> if (gen == generation) {
                    val page = result.data
                    pageNum = page.pageNum
                    _ui.update {
                        // pn 翻页理论上不重复，按 rpid 去重兜底
                        val merged = (it.replies + page.replies).distinctBy { c -> c.rpid }
                        it.copy(
                            replies = merged,
                            total = page.total,
                            loadingMore = false,
                            end = page.isEnd,
                        )
                    }
                }
                is Result.Error -> if (gen == generation) {
                    _ui.update { it.copy(loadingMore = false, appendError = result.msg.ifBlank { "评论加载失败" }) }
                }
            }
        }
    }

    /** 加载更多失败后点重试：先清掉错误标记再复用同一入口 */
    fun retryLoadMore() {
        if (_ui.value.appendError == null) return
        _ui.update { it.copy(appendError = null) }
        loadMore()
    }
}
