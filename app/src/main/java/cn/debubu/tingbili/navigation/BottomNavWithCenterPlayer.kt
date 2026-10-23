package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import cn.debubu.tingbili.core.media.PlayerManager



@Composable
fun BottomNavWithCenterPlayer(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    visibility: MiniPlayerVisibility = rememberMiniPlayerVisibility(),
) {
    val playerManager = viewModel.playerManager

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination

    val isHomeSelected = destination?.hierarchy?.any { it.hasRoute<HomeRoute>() } == true
    val isPlaylistSelected = destination?.hierarchy?.any { it.hasRoute<PlaylistRoute>() } == true
    val isHistorySelected = destination?.hierarchy?.any { it.hasRoute<HistoryRoute>() } == true
    val isMeSelected = destination?.hierarchy?.any { it.hasRoute<MeRoute>() } == true
    // M3 默认配色：选中指示块为 secondaryContainer 胶囊
    val itemColors = NavigationBarItemDefaults.colors()
    // 推荐 tab 双击回顶（onClick lambda 非组合作用域，helper 必须在外层调）
    val homeTabClick = rememberHomeTabClick(navController, isHomeSelected, viewModel)

    Column {
        // 进度 tick（500ms）只重组这个槽位，底栏 4 个 Tab 不再跟着抖
        // 图片格式只包 mini 条：切格式不连带底栏 Tab 重组
        ImageFormatScope(viewModel) {
            MiniPlayerSlot(
                playerManager = playerManager,
                navController = navController,
                visibility = visibility,
            )
        }
        NavigationBar {
        NavigationBarItem(
            selected = isHomeSelected,
            onClick = homeTabClick,
            icon = {
                Icon(
                    imageVector = if (isHomeSelected) Icons.Filled.Explore else Icons.Outlined.Explore,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("推荐") }
        )
        NavigationBarItem(
            selected = isPlaylistSelected,
            onClick = {
                navController.navigate(PlaylistRoute) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                }
            },
            icon = {
                Icon(
                    imageVector = if (isPlaylistSelected) Icons.AutoMirrored.Filled.List else Icons.AutoMirrored.Outlined.List,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("收藏") }
        )
        NavigationBarItem(
            selected = isHistorySelected,
            onClick = {
                navController.navigate(HistoryRoute) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                }
            },
            icon = {
                Icon(
                    imageVector = if (isHistorySelected) Icons.Filled.History else Icons.Outlined.History,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("历史") }
        )
        NavigationBarItem(
            selected = isMeSelected,
            onClick = {
                navController.navigate(MeRoute) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                }
            },
            icon = {
                Icon(
                    imageVector = if (isMeSelected) Icons.Filled.Person else Icons.Outlined.Person,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("我的") }
        )
        }
    }
}

/**
 * mini 条独立槽位：整 [PlaybackState] 订阅下沉到这里。
 * position 每 500ms 推一次，之前订阅在底栏顶层，4 个 Tab 图标每秒重测；
 * 现在 tick 只重组这一个槽位。
 */
@Composable
private fun MiniPlayerSlot(
    playerManager: PlayerManager,
    navController: NavController,
    visibility: MiniPlayerVisibility,
) {
    val state by playerManager.state.collectAsStateWithLifecycle()
    val track = state.currentTrack
    val trackKey = track?.let { "${it.bvid}:${it.cid}" }
    LaunchedEffect(trackKey) { visibility.onTrackChanged(trackKey) }
    if (track == null || trackKey == null) return
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    MiniPlayerBar(
        track = track,
        isPlaying = state.isPlaying,
        isLoading = state.isLoading,
        onToggle = { playerManager.toggle() },
        onOpen = {
            navController.navigate(PlayerRoute) { launchSingleTop = true }
        },
        progress = progress,
        // 布局占位只看手动关闭；滚动隐藏走位移，不触发 Scaffold 重测
        visible = !visibility.isDismissed(trackKey),
        scrollVisible = visibility.scrollVisible,
        onDismiss = { visibility.dismiss(trackKey) },
    )
}
