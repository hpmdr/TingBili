package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import cn.debubu.tingbili.feature.detail.DetailScreen
import cn.debubu.tingbili.feature.history.HistoryScreen
import cn.debubu.tingbili.feature.home.HomeScreen
import cn.debubu.tingbili.feature.player.PlayerScreen
import cn.debubu.tingbili.feature.playlist.PlaylistScreen
import cn.debubu.tingbili.feature.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object PlaylistRoute

@Serializable
object HistoryRoute

@Serializable
object SettingsRoute

@Serializable
object PlayerRoute

/** 视频详情页路由，携带 BV 号 */
@Serializable
data class VideoDetailRoute(val bvid: String)

@Composable
fun AppNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier.padding(innerPadding)
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onTrackToDetail = { bvid ->
                    navController.navigate(VideoDetailRoute(bvid))
                }
            )
        }
        composable<VideoDetailRoute> {
            // bvid 通过 SavedStateHandle 注入 DetailViewModel
            DetailScreen(
                onBack = { navController.popBackStack() },
                onPlayNavigate = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
            )
        }
        composable<PlaylistRoute> {
            PlaylistScreen()
        }
        composable<HistoryRoute> {
            HistoryScreen()
        }
        composable<SettingsRoute> {
            SettingsScreen()
        }
        composable<PlayerRoute> {
            PlayerScreen()
        }
    }
}
