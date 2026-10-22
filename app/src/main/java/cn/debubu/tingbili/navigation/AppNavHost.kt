package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import cn.debubu.tingbili.feature.player.PlayerViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import cn.debubu.tingbili.feature.detail.DetailScreen
import cn.debubu.tingbili.feature.mine.LoginScreen
import cn.debubu.tingbili.feature.history.HistoryScreen
import cn.debubu.tingbili.feature.home.HomeScreen
import cn.debubu.tingbili.feature.player.PlayerScreen
import cn.debubu.tingbili.feature.playlist.PlaylistDetailScreen
import cn.debubu.tingbili.feature.playlist.PlaylistScreen
import cn.debubu.tingbili.feature.search.SearchScreen
import cn.debubu.tingbili.feature.mine.AppearanceScreen
import cn.debubu.tingbili.feature.mine.BiliFavDetailScreen
import cn.debubu.tingbili.feature.mine.BiliFavFoldersScreen
import cn.debubu.tingbili.feature.mine.BiliHistoryScreen
import cn.debubu.tingbili.feature.mine.BiliToviewScreen
import cn.debubu.tingbili.feature.mine.MeScreen
import cn.debubu.tingbili.feature.mine.OtherSettingsScreen
import cn.debubu.tingbili.feature.mine.SettingsScreen

@Composable
fun AppRootNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    /** 冷启动首 Tab：MainActivity 按本地收藏有无决定，默认推荐（预览/旧入口兼容） */
    startTab: Any = HomeRoute,
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    NavHost(
        navController = navController,
        startDestination = MainTabsRoute,
        modifier = modifier
    ) {
        // 主页 Tab 容器（带底栏，见 MainTabsScaffold）
        navigation<MainTabsRoute>(startDestination = startTab) {
            composable<HomeRoute> {
                ImageFormatScope(mainViewModel) {
                    HomeScreen(
                        onTrackToDetail = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                        onSearchClick = { navController.navigate(SearchRoute) }
                    )
                }
            }
            composable<PlaylistRoute> {
                ImageFormatScope(mainViewModel) {
                    PlaylistScreen(onPlaylistClick = { id -> navController.navigate(PlaylistDetailRoute(id)) })
                }
            }
            composable<HistoryRoute> {
                ImageFormatScope(mainViewModel) { HistoryScreen() }
            }
            composable<MeRoute> {
                MeScreen(
                    onOpenAppearance = { navController.navigate(AppearanceRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenOtherSettings = { navController.navigate(OtherSettingsRoute) },
                    onOpenBiliFav = { navController.navigate(BiliFavFoldersRoute) },
                    onOpenBiliHistory = { navController.navigate(BiliHistoryRoute) },
                    onOpenBiliToview = { navController.navigate(BiliToviewRoute) },
                    onOpenLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
                )
            }
        }
        // 独立全屏页：无底栏（登录）
        composable<LoginRoute> {
            LoginScreen(
                onBack = { navController.popBackStack() },
                onSuccess = { navController.popBackStack() },
            )
        }
        // 独立全屏页：无底栏（「我的」的二级页）
        composable<AppearanceRoute> {
            AppearanceScreen(onBack = { navController.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<OtherSettingsRoute> {
            OtherSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<BiliFavFoldersRoute> {
            BiliFavFoldersScreen(
                onBack = { navController.popBackStack() },
                onFolderClick = { id, title -> navController.navigate(BiliFavDetailRoute(id, title)) },
                onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
            )
        }
        composable<BiliFavDetailRoute> { entry ->
            val args = entry.toRoute<BiliFavDetailRoute>()
            BiliFavDetailScreen(
                mediaId = args.mediaId,
                title = args.title,
                onBack = { navController.popBackStack() },
                onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
            )
        }
        composable<BiliHistoryRoute> {
            BiliHistoryScreen(
                onBack = { navController.popBackStack() },
                onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
            )
        }
        composable<BiliToviewRoute> {
            BiliToviewScreen(
                onBack = { navController.popBackStack() },
                onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
            )
        }
        composable<SearchRoute> {
            ImageFormatScope(mainViewModel) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onTrackToDetail = { bvid -> navController.navigate(VideoDetailRoute(bvid)) }
                )
            }
        }
        composable<VideoDetailRoute> {
            ImageFormatScope(mainViewModel) {
                DetailScreen(
                    onBack = { navController.popBackStack() },
                    onPlayNavigate = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
                )
            }
        }
        composable<PlaylistDetailRoute> {
            ImageFormatScope(mainViewModel) {
                PlaylistDetailScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<PlayerRoute> { backStackEntry ->
            // 播放页 VM 挂到 Tabs 容器条目上：播着的时候退多少次、进多少次都是同一个 VM，
            // 状态（进度/歌词/字幕选择）不断，uiState 也不重建。Tabs 条目与应用前台同寿，
            // 进程起来它就在，退出应用才销毁；空闲时 VM 只剩轻量流，无实质占用。
            val tabsEntry = remember(backStackEntry) {
                navController.getBackStackEntry(MainTabsRoute)
            }
            val vm: PlayerViewModel = hiltViewModel(tabsEntry)
            ImageFormatScope(mainViewModel) {
                PlayerScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onLoginClick = { navController.navigate(LoginRoute) { launchSingleTop = true } },
                onDetailClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                onSettingsClick = { navController.navigate(SettingsRoute) },
            )
            }
        }
    }
}

// 兼容旧入口：仅用于 Tab 内部预览，正式入口为 AppRootNavHost
@Composable
@Deprecated("改用 AppRootNavHost + MainTabsScaffold")
fun AppNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    AppRootNavHost(navController = navController, modifier = modifier.padding(innerPadding))
}
