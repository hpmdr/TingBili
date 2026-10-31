package cn.debubu.tingbili.feature.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.biliui.BiliErrorBox
import cn.debubu.tingbili.core.biliui.BiliNoAuthGate
import cn.debubu.tingbili.core.biliui.BiliVideoList
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.ui.component.BackNavIcon
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.dto.BiliSpaceVideo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class BiliToviewViewModel @Inject constructor(
    private val repo: BiliRepository,
    val auth: AuthRepository,
) : ViewModel() {
    val session = auth.sessionFlow
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _list = MutableStateFlow<List<BiliSpaceVideo>>(emptyList())
    val list: StateFlow<List<BiliSpaceVideo>> = _list.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()
    private var pn = 1

    fun load() {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = repo.getToview(pn = 1, ps = 20)) {
                is Result.Success -> {
                    _list.value = r.data.first
                    _hasMore.value = r.data.first.size >= 20
                    pn = 1
                }
                is Result.Error -> _error.value = r.msg.ifBlank { "加载失败" }
            }
            _loading.value = false
        }
    }

    fun loadMore() {
        if (!_hasMore.value || _loadingMore.value) return
        _loadingMore.value = true
        viewModelScope.launch {
            when (val r = repo.getToview(pn = pn + 1, ps = 20)) {
                is Result.Success -> {
                    _list.value = _list.value + r.data.first
                    _hasMore.value = r.data.first.size >= 20
                    pn += 1
                }
                is Result.Error -> Unit
            }
            _loadingMore.value = false
        }
    }
}

@Composable
fun BiliToviewScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onLogin: () -> Unit,
    vm: BiliToviewViewModel = hiltViewModel(),
) {
    val session by vm.session.collectAsStateWithLifecycle()
    if (session == null) {
        BiliNoAuthGate(title = "稍后再看", onBack = onBack, onLogin = onLogin)
        return
    }
    LaunchedEffect(Unit) { vm.load() }
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "稍后再看",
                navigationIcon = { BackNavIcon(onBack = onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && list.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                error != null && list.isEmpty() -> BiliErrorBox(
                    error ?: "加载失败",
                    onRetry = { vm.load() },
                )

                else -> BiliVideoList(
                    list = list,
                    hasMore = hasMore,
                    loadingMore = loadingMore,
                    onLoadMore = { vm.loadMore() },
                    onItemClick = { onVideoClick(it.bvid) },
                    emptyText = "稍后再看是空的",
                )
            }
        }
    }
}
