package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/** 主页 4 Tab 的壳：窄屏底栏 + 悬浮 mini 条；宽屏 Rail + 右下悬浮 mini 条 */
@Composable
fun MainTabsScaffold(
    navController: NavHostController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    // 宽 >= 600dp 一律 Rail：实测本机横屏（约 904dp）官方默认映射仍返回
    // NavigationBar，且 Expanded 会走向抽屉——手机横屏要的是左侧 Rail。
    val layoutType = if (!adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(600)) {
        NavigationSuiteType.NavigationBar
    } else {
        NavigationSuiteType.NavigationRail
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination
    val isHomeSelected = destination?.hierarchy?.any { it.hasRoute<HomeRoute>() } == true
    val isPlaylistSelected = destination?.hierarchy?.any { it.hasRoute<PlaylistRoute>() } == true
    val isHistorySelected = destination?.hierarchy?.any { it.hasRoute<HistoryRoute>() } == true
    val isSettingsSelected = destination?.hierarchy?.any { it.hasRoute<SettingsRoute>() } == true
    // M3 默认配色：选中指示块为 secondaryContainer 胶囊
    val itemColors = NavigationSuiteDefaults.itemColors()

    if (layoutType == NavigationSuiteType.NavigationBar) {
        Scaffold(
            bottomBar = { BottomNavWithCenterPlayer(navController) }
        ) { innerPadding ->
            AppRootNavHost(navController = navController, modifier = Modifier.padding(innerPadding))
        }
    } else {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                item(
                    selected = isHomeSelected,
                    onClick = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true; restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (isHomeSelected) Icons.Filled.Explore else Icons.Outlined.Explore,
                            contentDescription = null
                        )
                    },
                    colors = itemColors,
                    label = { Text("推荐") }
                )
                item(
                    selected = isPlaylistSelected,
                    onClick = {
                        navController.navigate(PlaylistRoute) {
                            launchSingleTop = true; restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
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
                item(
                    selected = isHistorySelected,
                    onClick = {
                        navController.navigate(HistoryRoute) {
                            launchSingleTop = true; restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
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
                item(
                    selected = isSettingsSelected,
                    onClick = {
                        navController.navigate(SettingsRoute) {
                            launchSingleTop = true; restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (isSettingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = null
                        )
                    },
                    colors = itemColors,
                    label = { Text("设置") }
                )
            },
            layoutType = layoutType
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppRootNavHost(navController = navController, modifier = Modifier.fillMaxSize())
                val playerManager = viewModel.playerManager
                val state by playerManager.state.collectAsStateWithLifecycle()
                val track = state.currentTrack
                if (track != null) {
                    MiniPlayerBar(
                        track = track,
                        isPlaying = state.isPlaying,
                        isLoading = state.isLoading,
                        onToggle = { playerManager.toggle() },
                        onOpen = { navController.navigate(PlayerRoute) { launchSingleTop = true } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .width(320.dp)
                    )
                }
            }
        }
    }
}
