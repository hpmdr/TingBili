package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.dto.BiliFavFolder
import cn.debubu.tingbili.data.bilibili.dto.BiliSpaceVideo
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── 收藏夹列表 ──

@HiltViewModel
class BiliFavFoldersViewModel @Inject constructor(
    private val repo: BiliRepository,
    val auth: AuthRepository,
) : ViewModel() {
    val session = auth.sessionFlow
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _folders = MutableStateFlow<List<BiliFavFolder>>(emptyList())
    val folders: StateFlow<List<BiliFavFolder>> = _folders.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()
    private var pn = 1

    fun load() {
        val uid = auth.sessionFlow.value?.uid ?: return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = repo.getFavFolders(uid, pn = 1, ps = 20)) {
                is Result.Success -> {
                    _folders.value = r.data.first
                    _hasMore.value = r.data.second
                    pn = 1
                }
                is Result.Error -> _error.value = r.msg ?: "加载失败"
            }
            _loading.value = false
        }
    }

    fun loadMore() {
        val uid = auth.sessionFlow.value?.uid ?: return
        if (!_hasMore.value) return
        viewModelScope.launch {
            when (val r = repo.getFavFolders(uid, pn = pn + 1, ps = 20)) {
                is Result.Success -> {
                    _folders.value = _folders.value + r.data.first
                    _hasMore.value = r.data.second
                    pn += 1
                }
                is Result.Error -> _error.value = r.msg ?: "加载失败"
            }
        }
    }
}

@Composable
fun BiliFavFoldersScreen(
    onBack: () -> Unit,
    onFolderClick: (Long, String) -> Unit,
    onLogin: () -> Unit,
    vm: BiliFavFoldersViewModel = hiltViewModel(),
) {
    val session by vm.session.collectAsStateWithLifecycle()
    if (session == null) {
        BiliNoAuthGate(title = "B站收藏", onBack = onBack, onLogin = onLogin)
        return
    }
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(session?.uid) { vm.load() }
    TingBiliScaffold(topBar = { TingBiliTopAppBar(title = "B站收藏", navigationIcon = { BackNavIcon(onBack = onBack) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && folders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && folders.isEmpty() -> BiliErrorBox(error ?: "加载失败", onRetry = { vm.load() })
                folders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无收藏夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(folders, key = { it.id }) { folder ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onFolderClick(folder.id, folder.title) },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = folder.cover.biliImage(192, 120),
                                    contentDescription = null,
                                    modifier = Modifier.size(width = 96.dp, height = 60.dp).clip(MaterialTheme.shapes.medium),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(folder.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${folder.count} 个视频", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (hasMore) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { vm.loadMore() }) { Text("加载更多") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 夹内视频 ──

@HiltViewModel
class BiliFavDetailViewModel @Inject constructor(
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
    private var mediaId = 0L

    fun bind(mediaId: Long) {
        if (this.mediaId != mediaId) {
            this.mediaId = mediaId
            _list.value = emptyList()
            pn = 1
            load()
        }
    }

    fun load() {
        if (mediaId == 0L) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            when (val r = repo.getFavMedias(mediaId, pn = 1, ps = 20)) {
                is Result.Success -> {
                    _list.value = r.data.first
                    _hasMore.value = r.data.second
                    pn = 1
                }
                is Result.Error -> _error.value = r.msg ?: "加载失败"
            }
            _loading.value = false
        }
    }

    fun loadMore() {
        if (!_hasMore.value || _loadingMore.value) return
        _loadingMore.value = true
        viewModelScope.launch {
            when (val r = repo.getFavMedias(mediaId, pn = pn + 1, ps = 20)) {
                is Result.Success -> {
                    _list.value = _list.value + r.data.first
                    _hasMore.value = r.data.second
                    pn += 1
                }
                is Result.Error -> _error.value = r.msg ?: "加载失败"
            }
            _loadingMore.value = false
        }
    }
}

@Composable
fun BiliFavDetailScreen(
    mediaId: Long,
    title: String,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onLogin: () -> Unit,
    vm: BiliFavDetailViewModel = hiltViewModel(),
) {
    val session by vm.session.collectAsStateWithLifecycle()
    if (session == null) {
        BiliNoAuthGate(title = title.ifBlank { "收藏详情" }, onBack = onBack, onLogin = onLogin)
        return
    }
    androidx.compose.runtime.LaunchedEffect(mediaId) { vm.bind(mediaId) }
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    TingBiliScaffold(topBar = { TingBiliTopAppBar(title = title.ifBlank { "收藏详情" }, navigationIcon = { BackNavIcon(onBack = onBack) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && list.isEmpty() -> BiliErrorBox(error ?: "加载失败", onRetry = { vm.load() })
                else -> BiliVideoList(
                    list = list,
                    hasMore = hasMore,
                    loadingMore = loadingMore,
                    onLoadMore = { vm.loadMore() },
                    onItemClick = { onVideoClick(it.bvid) },
                    emptyText = "该收藏夹暂无视频",
                )
            }
        }
    }
}
