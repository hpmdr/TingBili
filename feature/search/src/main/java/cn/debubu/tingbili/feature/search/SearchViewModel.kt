package cn.debubu.tingbili.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository
) : ViewModel() {

    private val _keyword = MutableStateFlow(savedStateHandle.get<String>("keyword").orEmpty())
    val keyword: StateFlow<String> = _keyword.asStateFlow()

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
