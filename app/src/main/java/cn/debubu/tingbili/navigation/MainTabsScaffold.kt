package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
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

/** 主页 4 Tab 的壳：仅在此显示底栏 + 圆形 mini 播放器 */
@Composable
fun MainTabsScaffold(
    navController: NavHostController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination
    val isHomeSelected = destination?.hierarchy?.any { it.hasRoute<HomeRoute>() } == true
    val isPlaylistSelected = destination?.hierarchy?.any { it.hasRoute<PlaylistRoute>() } == true
    val isHistorySelected = destination?.hierarchy?.any { it.hasRoute<HistoryRoute>() } == true
    val isSettingsSelected = destination?.hierarchy?.any { it.hasRoute<SettingsRoute>() } == true
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = selectedColor,
            selectedTextColor = selectedColor,
            indicatorColor = selectedColor.copy(alpha = 0.14f),
            unselectedIconColor = unselectedColor,
            unselectedTextColor = unselectedColor
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = selectedColor,
            selectedTextColor = selectedColor,
            indicatorColor = selectedColor.copy(alpha = 0.14f),
            unselectedIconColor = unselectedColor,
            unselectedTextColor = unselectedColor
        ),
        navigationDrawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedIconColor = selectedColor,
            selectedTextColor = selectedColor,
            unselectedIconColor = unselectedColor,
            unselectedTextColor = unselectedColor
        )
    )

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
                            imageVector = if (isHomeSelected) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = null
                        )
                    },
                    colors = itemColors,
                    label = { Text("首页") }
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
                val progress = if (state.durationMs > 0L) {
                    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularMiniPlayer(
                        progress = progress,
                        cover = state.currentTrack?.cover ?: "",
                        isPlaying = state.isPlaying,
                        isLoading = state.isLoading,
                        onClick = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
                    )
                }
            }
        }
    }
}
