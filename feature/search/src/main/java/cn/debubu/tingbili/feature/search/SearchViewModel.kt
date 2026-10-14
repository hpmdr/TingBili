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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository,
    prefs: PreferencesRepository
) : ViewModel() {

    private val _keyword = MutableStateFlow(savedStateHandle.get<String>("keyword").orEmpty())
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    /**
     * 首页推荐默认词（第一个策展关键词）：搜索页输入为空时的 placeholder 提示，
     * 空输入回车/点搜索则按此词搜。设置页改词后自动跟随。
     */
    val defaultKeyword: StateFlow<String> = prefs.homeKeywords
        .map { it.firstOrNull().orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingFlow: Flow<PagingData<Track>> = _keyword.flatMapLatest { kw ->
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            SearchPagingSource(repo, kw)
        }.flow.cachedIn(viewModelScope)
    }

    fun onSearch(kw: String) {
        _keyword.value = kw.trim()
    }
}
