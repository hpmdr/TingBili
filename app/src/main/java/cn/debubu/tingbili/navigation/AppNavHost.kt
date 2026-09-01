package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
            PlaceholderScreen("首页")
        }
        composable<PlaylistRoute> {
            PlaceholderScreen("歌单")
        }
        composable<HistoryRoute> {
            PlaceholderScreen("历史")
        }
        composable<SettingsRoute> {
            PlaceholderScreen("设置")
        }
        composable<PlayerRoute> {
            PlaceholderScreen("播放页")
        }
    }
}

@Composable
private fun PlaceholderScreen(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = name)
    }
}
