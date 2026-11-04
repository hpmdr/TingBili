package cn.debubu.tingbili.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.FavoriteFolderSummary
import cn.debubu.tingbili.core.ui.MiniPlayerClearance
import cn.debubu.tingbili.core.ui.component.CreateFolderDialog
import cn.debubu.tingbili.core.ui.component.MessageSnackbarEffect
import cn.debubu.tingbili.core.ui.component.TingBiliConfirmDialog
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliSnackbarHost
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/** 统一列表项：手动收藏夹或 BV 收藏，按「最近添加」倒序混排。 */
private data class UnifiedFavorite(
    val key: String,
    val addedAt: Long,
    val folder: FavoriteFolderSummary? = null,
    val bv: BvFavoriteEntity? = null,
)

@Composable
fun FavoritesScreen(
    onFolderClick: (Long) -> Unit,
    onBvClick: (String) -> Unit,
    onBiliClick: () -> Unit,
    vm: FavoritesViewModel = hiltViewModel(),
) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val bvFavorites by vm.bvFavorites.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val defaultFolder = folders.firstOrNull { it.folder.isDefault }
    val manualFolders = folders.filterNot { it.folder.isDefault }

    // 手动夹与 BV 不再分区：统一按添加时间倒序（收藏夹用创建时间，BV 用收藏时间）
    val unified = remember(manualFolders, bvFavorites) {
        buildList {
            manualFolders.forEach {
                add(UnifiedFavorite("folder_${it.folder.id}", it.folder.createdAt, folder = it))
            }
            bvFavorites.forEach {
                add(UnifiedFavorite("bv_${it.bvid}", it.addedAt, bv = it))
            }
        }.sortedByDescending { it.addedAt }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<BvFavoriteEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    MessageSnackbarEffect(message, snackbarHostState, vm::consumeMessage)

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "收藏",
                subtitle = if (loaded) {
                    "${manualFolders.size} 个收藏夹 · ${bvFavorites.size} 个 BV"
                } else null,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建收藏夹") },
                                onClick = {
                                    menuOpen = false
                                    showCreate = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { TingBiliSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!loaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@TingBiliScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 8.dp,
                end = 12.dp,
                bottom = 12.dp + MiniPlayerClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "my_favorite") {
                defaultFolder?.let { summary ->
                    FavoriteFolderCard(
                        summary = summary,
                        onClick = { onFolderClick(summary.folder.id) },
                        tag = "收藏夹",
                    )
                } ?: EmptyHint("我的最爱初始化中")
            }
            item(key = "bili_entry") {
                BiliEntryCard(loggedIn = loggedIn, onClick = onBiliClick)
            }
            if (unified.isEmpty()) {
                item(key = "unified_empty") {
                    EmptyHint("还没有收藏夹或 BV 收藏，点右上角新建收藏夹")
                }
            } else {
                items(unified, key = { it.key }) { item ->
                    val folder = item.folder
                    val bv = item.bv
                    when {
                        folder != null -> FavoriteFolderCard(
                            summary = folder,
                            onClick = { onFolderClick(folder.folder.id) },
                            tag = "收藏夹",
                        )
                        bv != null -> BvFavoriteCard(
                            favorite = bv,
                            onClick = { onBvClick(bv.bvid) },
                            onLongClick = { cancelTarget = bv },
                            tag = "BV",
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateFolderDialog(
            onConfirm = {
                vm.createFolder(it)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    cancelTarget?.let { favorite ->
        TingBiliConfirmDialog(
            title = "取消 BV 收藏？",
            text = "将删除「${favorite.title}」及其本地缓存内容。",
            confirmLabel = "取消收藏",
            destructive = true,
            onConfirm = {
                vm.removeBvFavorite(favorite.bvid)
                cancelTarget = null
            },
            onDismiss = { cancelTarget = null },
        )
    }
}

