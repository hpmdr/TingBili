package cn.debubu.tingbili.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.FavoriteFolderSummary
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    auth: AuthRepository,
) : ViewModel() {

    val folders: StateFlow<List<FavoriteFolderSummary>> =
        favoritesRepository.observeFolderSummaries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bvFavorites: StateFlow<List<BvFavoriteEntity>> =
        favoritesRepository.observeBvFavorites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val loggedIn: StateFlow<Boolean> = auth.sessionFlow
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { favoritesRepository.ensureDefaultFolder() }
                .onFailure { _message.value = it.message ?: "收藏夹初始化失败" }
            _loaded.value = true
        }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { favoritesRepository.createFolder(trimmed) }
                .onSuccess { _message.value = "已新建「$trimmed」" }
                .onFailure { _message.value = it.message ?: "新建收藏夹失败" }
        }
    }

    fun removeBvFavorite(bvid: String) {
        viewModelScope.launch {
            runCatching { favoritesRepository.removeBvFavorite(bvid) }
                .onSuccess { _message.value = "已取消 BV 收藏" }
                .onFailure { _message.value = it.message ?: "取消收藏失败" }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
