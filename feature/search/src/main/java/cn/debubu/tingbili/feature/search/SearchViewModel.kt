package cn.debubu.tingbili.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository,
    private val prefs: PreferencesRepository
) : ViewModel() {

    private val _keyword = MutableStateFlow(savedStateHandle.get<String>("keyword").orEmpty())
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    /**
     * 首页推荐默认词（第一个策展关键词）：搜索页输入为空时的 placeholder 提示，
     * 空输入回车/点搜索则按此词搜。设置页改词后自动跟随。
     */
    val defaultKeyword: StateFlow<String> = prefs.homeKeywords
        .map { it.firstOrNull().orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 推荐搜索 chips（全部首页策展关键词），搜索页空态展示 */
    val suggestKeywords: StateFlow<List<String>> = prefs.homeKeywords
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 搜索历史（本地，最近在前），搜索页空态展示 */
    val searchHistory: StateFlow<List<String>> = prefs.searchHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前搜索词的结果总数（接口返回的全量命中数），结果计数条用 */
    private val _totalCount = MutableStateFlow<Int?>(null)
    val totalCount: StateFlow<Int?> = _totalCount.asStateFlow()

    // 数据层常驻：与 HomeViewModel 同理，进详情再回来秒回、无重载
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow: StateFlow<PagingData<Track>> = _keyword.flatMapLatest { kw ->
        Pager(
            PagingConfig(
                pageSize = 20,
                prefetchDistance = 8,
                initialLoadSize = 20,
                maxSize = 200,
                enablePlaceholders = false
            )
        ) {
            SearchPagingSource(repo, kw, onTotalLoaded = { _totalCount.value = it })
        }.flow.cachedIn(viewModelScope)
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PagingData.empty())

    fun onSearch(kw: String) {
        val q = kw.trim()
        if (q.isEmpty()) return
        _keyword.value = q
        _totalCount.value = null
        viewModelScope.launch { prefs.addSearchHistory(q) }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { prefs.removeSearchHistory(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { prefs.clearSearchHistory() }
    }
}
