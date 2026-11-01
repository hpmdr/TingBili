package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
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
import cn.debubu.tingbili.feature.space.SpaceScreen
import cn.debubu.tingbili.feature.player.PlayerScreen
import cn.debubu.tingbili.feature.favorites.BvFavoriteScreen
import cn.debubu.tingbili.feature.favorites.FavoriteFolderScreen
import cn.debubu.tingbili.feature.mine.AppearanceScreen
import cn.debubu.tingbili.feature.mine.AudioDecodersScreen
import cn.debubu.tingbili.feature.mine.GeneralSettingsScreen
import cn.debubu.tingbili.feature.favorites.BiliFavDetailScreen
import cn.debubu.tingbili.feature.favorites.BiliFavoritesScreen
import cn.debubu.tingbili.feature.favorites.BiliToviewScreen
import cn.debubu.tingbili.feature.mine.BiliHistoryScreen
import cn.debubu.tingbili.feature.mine.MeScreen
import cn.debubu.tingbili.feature.mine.StorageSettingsScreen
import cn.debubu.tingbili.feature.mine.QualityScreen
import cn.debubu.tingbili.feature.mine.SettingsScreen

@Composable
fun AppRootNavHost(
    navController: NavHostController,
    /** 4 Tab 的分页状态：与底栏共享，冷启动首 Tab 由调用方的 initialPage 决定 */
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = hiltViewModel(),
    /** 通知点击要求直达播放页（P1）：一次性标记，消费后由上层复位 */
    openPlayerOnStart: Boolean = false,
    onOpenPlayerConsumed: () -> Unit = {},
) {

    NavHost(
        navController = navController,
        startDestination = MainTabsRoute,
        modifier = modifier,
        enterTransition = SharedAxisXEnter,
        exitTransition = SharedAxisXExit,
        popEnterTransition = SharedAxisXPopEnter,
        popExitTransition = SharedAxisXPopExit,
        predictivePopEnterTransition = PredictiveSharedAxisXPopEnter,
        predictivePopExitTransition = PredictiveSharedAxisXPopExit,
    ) {
        // 主页 Tab 容器（带底栏，见 MainTabsScaffold）
        // 主页 4 Tab：单个 destination + 横向分页（底栏点击与左右滑都在 pager 内）
        composable<MainTabsRoute> {
            // 通知点击直达播放页（P1）：放在 Tab 容器内部触发，此时 NavHost 图必然已挂好，
            // 不会像放在 NavHost 之前那样撞上「graph has not been set」。
            if (openPlayerOnStart) {
                LaunchedEffect(Unit) {
                    navController.navigate(PlayerRoute) { launchSingleTop = true }
                    onOpenPlayerConsumed()
                }
            }
            MainTabsPager(
                navController = navController,
                mainViewModel = mainViewModel,
                pagerState = pagerState,
            )
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
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAudioDecoders = { navController.navigate(AudioDecodersRoute) },
                onOpenQuality = { navController.navigate(QualityRoute) },
            )
        }
        composable<QualityRoute> {
            QualityScreen(onBack = { navController.popBackStack() })
        }
        composable<StorageRoute> {
            StorageSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<GeneralRoute> {
            GeneralSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<AudioDecodersRoute> {
            AudioDecodersScreen(onBack = { navController.popBackStack() })
        }
        composable<BiliFavoritesRoute> {
            ImageFormatScope(mainViewModel) {
                BiliFavoritesScreen(
                    onBack = { navController.popBackStack() },
                    onFolderClick = { id, title -> navController.navigate(BiliFavDetailRoute(id, title)) },
                    onToviewClick = { navController.navigate(BiliToviewRoute) },
                    onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
                )
            }
        }
        composable<BiliFavDetailRoute> { entry ->
            val args = entry.toRoute<BiliFavDetailRoute>()
            ImageFormatScope(mainViewModel) {
                BiliFavDetailScreen(
                    mediaId = args.mediaId,
                    title = args.title,
                    onBack = { navController.popBackStack() },
                    onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                    onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
                )
            }
        }
        composable<BiliHistoryRoute> {
            ImageFormatScope(mainViewModel) {
                BiliHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                    onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
                )
            }
        }
        composable<BiliToviewRoute> {
            ImageFormatScope(mainViewModel) {
                BiliToviewScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                    onLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
                )
            }
        }
        composable<VideoDetailRoute> {
            ImageFormatScope(mainViewModel) {
                DetailScreen(
                    onBack = { navController.popBackStack() },
                    onPlayNavigate = { navController.navigate(PlayerRoute) { launchSingleTop = true } },
                    onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                    onUpClick = { mid -> navController.navigate(UpSpaceRoute(mid)) }
                )
            }
        }
        composable<UpSpaceRoute> {
            ImageFormatScope(mainViewModel) {
                SpaceScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { bvid -> navController.navigate(VideoDetailRoute(bvid)) }
                )
            }
        }
        composable<FavoriteFolderRoute> {
            ImageFormatScope(mainViewModel) {
                FavoriteFolderScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<BvFavoriteRoute> { entry ->
            val args = entry.toRoute<BvFavoriteRoute>()
            ImageFormatScope(mainViewModel) {
                BvFavoriteScreen(
                    bvid = args.bvid,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable<PlayerRoute>(
            enterTransition = PlayerEnter,
            exitTransition = PlayerExit,
            popEnterTransition = PlayerPopEnter,
            popExitTransition = PlayerPopExit,
        ) { backStackEntry ->
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
                onUpClick = { mid -> navController.navigate(UpSpaceRoute(mid)) },
                onSettingsClick = { navController.navigate(SettingsRoute) },
            )
            }
        }
    }
}

// 旧入口 AppNavHost 已删除：4 Tab 改为 HorizontalPager 后，唯一入口是 AppRootNavHost
