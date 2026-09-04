package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
            HomeScreen()
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
