package cn.debubu.tingbili.feature.favorites

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.biliui.BiliErrorBox
import cn.debubu.tingbili.core.biliui.BiliNoAuthGate
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.MiniPlayerClearance
import cn.debubu.tingbili.core.ui.component.BackNavIcon
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.dto.BiliFavFolder
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class BiliFavoritesViewModel @Inject constructor(
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

    fun load() {
        val uid = auth.sessionFlow.value?.uid ?: return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            when (val result = repo.getAllFavFolders(uid)) {
                is Result.Success -> _folders.value = result.data
                is Result.Error -> _error.value = result.msg.ifBlank { "加载失败" }
            }
            _loading.value = false
        }
    }
}

@Composable
fun BiliFavoritesScreen(
    onBack: () -> Unit,
    onFolderClick: (Long, String) -> Unit,
    onToviewClick: () -> Unit,
    onLogin: () -> Unit,
    vm: BiliFavoritesViewModel = hiltViewModel(),
) {
    val session by vm.session.collectAsStateWithLifecycle()
    if (session == null) {
        BiliNoAuthGate(title = "B站收藏", onBack = onBack, onLogin = onLogin)
        return
    }

    LaunchedEffect(session?.uid) { vm.load() }
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()

    val defaultFolder = folders.firstOrNull { it.isDefault }
    val createdFolders = folders.filterNot { it.isDefault }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "B站收藏",
                navigationIcon = { BackNavIcon(onBack = onBack) },
                actions = {
                    IconButton(onClick = { vm.load() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && folders.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                error != null && folders.isEmpty() -> BiliErrorBox(
                    message = error ?: "加载失败",
                    onRetry = { vm.load() },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp + MiniPlayerClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "toview") {
                        ToviewEntry(onClick = onToviewClick)
                    }

                    defaultFolder?.let { folder ->
                        item(key = "default_header") {
                            SectionTitle("默认收藏夹")
                        }
                        item(key = "default_${folder.id}") {
                            BiliFolderCard(
                                folder = folder,
                                onClick = { onFolderClick(folder.id, folder.title) },
                            )
                        }
                    }

                    item(key = "created_header") {
                        SectionTitle("我创建的收藏夹")
                    }

                    if (createdFolders.isEmpty()) {
                        item(key = "created_empty") {
                            Text(
                                text = if (defaultFolder != null) {
                                    "还没有创建其他收藏夹"
                                } else {
                                    "暂无收藏夹"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            )
                        }
                    } else {
                        items(createdFolders, key = { it.id }) { folder ->
                            BiliFolderCard(
                                folder = folder,
                                onClick = { onFolderClick(folder.id, folder.title) },
                            )
                        }
                    }

                    if (error != null) {
                        item(key = "error_banner") {
                            Text(
                                text = "刷新失败：$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun ToviewEntry(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.WatchLater,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("稍后再看", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "B站账号的稍后再看列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BiliFolderCard(folder: BiliFavFolder, onClick: () -> Unit) {
    val imageFormat = LocalImageFormat.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbModifier = Modifier
                .size(width = 96.dp, height = 60.dp)
                .clip(MaterialTheme.shapes.medium)
            if (folder.cover.isBlank()) {
                Box(
                    modifier = thumbModifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = folder.cover.biliImage(BiliImageVariant.ListThumb, imageFormat),
                    contentDescription = null,
                    modifier = thumbModifier,
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = folder.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (folder.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "默认",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${folder.count} 个视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
