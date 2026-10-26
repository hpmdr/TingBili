package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/** 主页 4 Tab 的壳：窄屏底栏 + 贴底 mini 条；宽屏 Rail + 右下 mini 条 */
@Composable
fun MainTabsScaffold(
    navController: NavHostController,
    viewModel: MainViewModel = hiltViewModel(),
    startTab: Any = HomeRoute
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
    val isMeSelected = destination?.hierarchy?.any { it.hasRoute<MeRoute>() } == true
    // M3 默认配色：选中指示块为 secondaryContainer 胶囊
    val itemColors = NavigationSuiteDefaults.itemColors()
    // 推荐 tab 双击回顶（item{} 是 DSL 非组合作用域，helper 必须在外层调）
    val homeTabClick = rememberHomeTabClick(navController, isHomeSelected, viewModel)

    val miniPlayerVisibility = rememberMiniPlayerVisibility()
    // 壳常驻、NavHost 调用点唯一：在 tab 与独立页之间导航时不重建，转场动画才跑得起来
    val inTabs = isMainTabDestination(destination)
    if (layoutType == NavigationSuiteType.NavigationBar) {
        Scaffold(
            modifier = Modifier.nestedScroll(miniPlayerVisibility.connection),
            bottomBar = {
                if (inTabs) {
                    MainBottomNav(navController)
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                AppRootNavHost(navController = navController, startTab = startTab, mainViewModel = viewModel, modifier = Modifier.fillMaxSize())
                // 悬浮 mini：盖在列表上、不占位，列表从它后面滚过
                if (inTabs) {
                    MiniPlayerOverlay(
                        navController = navController,
                        visibility = miniPlayerVisibility,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 12.dp, end = 12.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    } else {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                if (inTabs) {
                item(
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
                    selected = isMeSelected,
                    onClick = {
                        navController.navigate(MeRoute) {
                            launchSingleTop = true; restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
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
            },
            layoutType = if (inTabs) layoutType else NavigationSuiteType.None
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppRootNavHost(navController = navController, startTab = startTab, mainViewModel = viewModel, modifier = Modifier.fillMaxSize())
                val playerManager = viewModel.playerManager
                val state by playerManager.state.collectAsStateWithLifecycle()
                val track = state.currentTrack
                val trackKey = track?.let { "${it.bvid}:${it.cid}" }
                var dismissedKey by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(trackKey) { if (trackKey != dismissedKey) dismissedKey = null }
                if (inTabs && track != null && trackKey != null) {
                    ImageFormatScope(viewModel) {
                        MiniPlayerBar(
                            track = track,
                            isPlaying = state.isPlaying,
                            isLoading = state.isLoading,
                            onToggle = { playerManager.toggle() },
                            onOpen = { navController.navigate(PlayerRoute) { launchSingleTop = true } },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .width(320.dp),
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            visible = dismissedKey != trackKey,
                            onDismiss = { dismissedKey = trackKey },
                        )
                    }
                }
            }
        }
    }
}
