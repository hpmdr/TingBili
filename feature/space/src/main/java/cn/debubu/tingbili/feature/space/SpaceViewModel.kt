package cn.debubu.tingbili.feature.space

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.data.bilibili.SpaceRepository
import cn.debubu.tingbili.data.bilibili.dto.UpSpaceInfo
import cn.debubu.tingbili.data.bilibili.dto.UpVideo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SpaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SpaceRepository,
) : ViewModel() {

    /** 路由参数：UP 的 mid */
    val mid: Long = savedStateHandle.get<Long>("mid") ?: 0L

    private val _info = MutableStateFlow<UpSpaceInfo?>(null)
    val info: StateFlow<UpSpaceInfo?> = _info.asStateFlow()

    private val _infoLoading = MutableStateFlow(true)
    val infoLoading: StateFlow<Boolean> = _infoLoading.asStateFlow()

    private val _infoError = MutableStateFlow<String?>(null)
    val infoError: StateFlow<String?> = _infoError.asStateFlow()

    /** 投稿总数（接口 page.count），头部展示；切换排序时按新排序重新取 */
    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _order = MutableStateFlow(SpaceOrder.PUBDATE)
    val order: StateFlow<SpaceOrder> = _order.asStateFlow()

    /** UP 内搜索词：空串 = 不过滤 */
    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    // 切排序 / 改搜索词 = 重建 Pager，回到第一页；cachedIn 保证旋转/返回不重拉
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow: StateFlow<PagingData<UpVideo>> = combine(_order, _searchKeyword) { o, kw -> o to kw }
        .flatMapLatest { (o, kw) ->
            Pager(
                PagingConfig(
                    pageSize = 30,
                    prefetchDistance = 6,
                    initialLoadSize = 30,
                    enablePlaceholders = false,
                    maxSize = 240
                )
            ) {
                SpacePagingSource(repo, mid, o.apiValue, kw) { total ->
                    // 搜索结果的 count 是过滤后的量，头部投稿数只认全量
                    if (kw.isBlank()) _total.value = total
                }
            }.flow.cachedIn(viewModelScope)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PagingData.empty())

    init {
        loadInfo()
    }

    fun setOrder(order: SpaceOrder) {
        if (order == _order.value) return
        _order.value = order
        _total.value = 0
    }

    fun setSearchKeyword(raw: String) {
        val q = raw.trim()
        if (q == _searchKeyword.value) return
        _searchKeyword.value = q
    }

    fun retryInfo() = loadInfo()

    private fun loadInfo() {
        if (mid <= 0) {
            _infoLoading.value = false
            _infoError.value = "UP 主信息缺失"
            return
        }
        viewModelScope.launch {
            _infoLoading.value = true
            _infoError.value = null
            when (val r = repo.info(mid)) {
                is Result.Success -> _info.value = r.data
                is Result.Error -> _infoError.value = r.msg
            }
            _infoLoading.value = false
        }
    }
}
