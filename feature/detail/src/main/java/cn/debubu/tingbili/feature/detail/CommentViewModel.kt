package cn.debubu.tingbili.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.model.BiliComment
import cn.debubu.tingbili.data.bilibili.model.CommentSort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentUiState(
    val sort: CommentSort = CommentSort.HOT,
    val comments: List<BiliComment> = emptyList(),
    val total: Int = 0,
    val upMid: Long = 0,
    /** resolveAid 后的真实 oid：楼中楼详情页要拿它调 reply/reply */
    val aid: Long = 0,
    /** 首屏 / 切换排序中：整页转圈 */
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val end: Boolean = false,
    /** 首屏失败（整页错误 + 重试） */
    val error: String? = null,
    /** 加载更多失败：列表保留，底部给重试 */
    val appendError: String? = null,
)

/**
 * BV 评论列表：游标分页 + 热门/最新切换。
 *
 * aid 由详情页带进来（路由参数）；缺失时用 bvid 走一次 view 接口兜底——
 * 那条请求命中 BiliRepository 的 5 分钟内存 memo，从详情页进来等于不花网络。
 */
@HiltViewModel
class CommentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository,
) : ViewModel() {

    private val bvid: String = savedStateHandle["bvid"] ?: ""
    private val routeAid: Long = savedStateHandle["aid"] ?: 0L

    /** 副标题用：评论接口不带视频标题，从详情页带过来 */
    val videoTitle: String = savedStateHandle["title"] ?: ""

    private val routeUpMid: Long = savedStateHandle["upMid"] ?: 0L

    private val _ui = MutableStateFlow(CommentUiState())
    val ui: StateFlow<CommentUiState> = _ui.asStateFlow()

    private var nextOffset = ""
    private var generation = 0
    private var loadJob: Job? = null

    init {
        reload()
    }

    fun setSort(sort: CommentSort) {
        if (_ui.value.sort == sort) return
        _ui.update { it.copy(sort = sort) }
        reload()
    }

    /** 首屏 / 重试 / 换排序：清空列表从头拉。in-flight 请求靠 [generation] 丢弃过期结果。 */
    fun reload() {
        loadJob?.cancel()
        val gen = ++generation
        nextOffset = ""
        val sort = _ui.value.sort
        _ui.update {
            it.copy(
                comments = emptyList(),
                loading = true,
                loadingMore = false,
                end = false,
                error = null,
                appendError = null,
            )
        }
        loadJob = viewModelScope.launch {
            val aid = resolveAid()
            if (aid <= 0L) {
                if (gen == generation) _ui.update { it.copy(loading = false, error = "BV 号无效，暂时读不到评论") }
                return@launch
            }
            when (val result = repo.getComments(aid, sort, offset = "")) {
                is Result.Success -> if (gen == generation) {
                    val page = result.data
                    nextOffset = page.nextOffset
                    _ui.update {
                        it.copy(
                            comments = page.comments,
                            total = page.total,
                            upMid = routeUpMid.takeIf { mid -> mid > 0 } ?: page.upMid,
                            aid = aid,
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
        val offset = nextOffset
        if (offset.isBlank()) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val aid = resolveAid()
            if (aid <= 0L) {
                if (gen == generation) _ui.update { it.copy(loadingMore = false, appendError = "BV 号无效") }
                return@launch
            }
            when (val result = repo.getComments(aid, state.sort, offset)) {
                is Result.Success -> if (gen == generation) {
                    val page = result.data
                    nextOffset = page.nextOffset
                    _ui.update {
                        // 游标分页理论上不重复，热门排序偶有榜单挪位，按 rpid 去重兜底
                        val merged = (it.comments + page.comments).distinctBy { c -> c.rpid }
                        it.copy(
                            comments = merged,
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

    private suspend fun resolveAid(): Long =
        routeAid.takeIf { it > 0L } ?: when (val r = repo.getViewDetail(bvid)) {
            is Result.Success -> r.data.aid
            is Result.Error -> 0L
        }
}
