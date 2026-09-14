package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import cn.debubu.tingbili.feature.detail.DetailScreen
import cn.debubu.tingbili.feature.history.HistoryScreen
import cn.debubu.tingbili.feature.home.HomeScreen
import cn.debubu.tingbili.feature.player.PlayerScreen
import cn.debubu.tingbili.feature.playlist.PlaylistDetailScreen
import cn.debubu.tingbili.feature.playlist.PlaylistScreen
import cn.debubu.tingbili.feature.search.SearchScreen
import cn.debubu.tingbili.feature.settings.SettingsScreen

@Composable
fun AppRootNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = MainTabsRoute,
        modifier = modifier
    ) {
        // 主页 Tab 容器（带底栏，见 MainTabsScaffold）
        navigation<MainTabsRoute>(startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    onTrackToDetail = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                    onSearchClick = { navController.navigate(SearchRoute) }
                )
            }
            composable<PlaylistRoute> {
                PlaylistScreen(onPlaylistClick = { id -> navController.navigate(PlaylistDetailRoute(id)) })
            }
            composable<HistoryRoute> { HistoryScreen() }
            composable<SettingsRoute> { SettingsScreen() }
        }
        // 独立全屏页：无底栏
        composable<SearchRoute> {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onTrackToDetail = { bvid -> navController.navigate(VideoDetailRoute(bvid)) }
            )
        }
        composable<VideoDetailRoute> {
            DetailScreen(
                onBack = { navController.popBackStack() },
                onPlayNavigate = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
            )
        }
        composable<PlaylistDetailRoute> {
            PlaylistDetailScreen(onBack = { navController.popBackStack() })
        }
        composable<PlayerRoute> { PlayerScreen(onBack = { navController.popBackStack() }) }
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
