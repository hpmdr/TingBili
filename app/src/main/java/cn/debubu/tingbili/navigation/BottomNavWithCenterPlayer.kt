package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val playerManager: PlayerManager
) : ViewModel()

@Composable
fun BottomNavWithCenterPlayer(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val playerManager = viewModel.playerManager
    val state by playerManager.state.collectAsStateWithLifecycle()
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val cover = state.currentTrack?.cover ?: ""
    val isPlaying = state.isPlaying

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isHomeSelected = currentRoute?.contains("HomeRoute") == true
    val isPlaylistSelected = currentRoute?.contains("PlaylistRoute") == true
    val isHistorySelected = currentRoute?.contains("HistoryRoute") == true
    val isSettingsSelected = currentRoute?.contains("SettingsRoute") == true

    NavigationBar {
        NavigationBarItem(
            selected = isHomeSelected,
            onClick = {
                navController.navigate(HomeRoute) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                }
            },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("首页") }
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
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("歌单") }
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            CircularMiniPlayer(
                progress = progress,
                cover = cover,
                isPlaying = isPlaying,
                onClick = {
                    navController.navigate(PlayerRoute) {
                        launchSingleTop = true
                    }
                },
                onPlayPause = {
                    playerManager.toggle()
                }
            )
        }
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
            icon = { Icon(Icons.Default.History, contentDescription = null) },
            label = { Text("历史") }
        )
        NavigationBarItem(
            selected = isSettingsSelected,
            onClick = {
                navController.navigate(SettingsRoute) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                }
            },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("设置") }
        )
    }
}
