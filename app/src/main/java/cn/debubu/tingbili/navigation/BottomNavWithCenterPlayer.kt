package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(
    val playerManager: PlayerManager,
    prefs: PreferencesRepository,
) : ViewModel() {
    val imageFormat = prefs.imageFormat.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImageFormat.AVIF,
    )
    val themeMode = prefs.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.DEFAULT,
    )
    val customThemeColor = prefs.customThemeColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreferencesRepository.DEFAULT_THEME_COLOR,
    )
}

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
    val isLoading = state.isLoading

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination

    val isHomeSelected = destination?.hierarchy?.any { it.hasRoute<HomeRoute>() } == true
    val isPlaylistSelected = destination?.hierarchy?.any { it.hasRoute<PlaylistRoute>() } == true
    val isHistorySelected = destination?.hierarchy?.any { it.hasRoute<HistoryRoute>() } == true
    val isSettingsSelected = destination?.hierarchy?.any { it.hasRoute<SettingsRoute>() } == true
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

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
            icon = {
                Icon(
                    imageVector = if (isHomeSelected) Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = null
                )
            },
            colors = itemColors,
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
            icon = {
                Icon(
                    imageVector = if (isPlaylistSelected) Icons.AutoMirrored.Filled.List else Icons.AutoMirrored.Outlined.List,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("收藏") }
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            CircularMiniPlayer(
                progress = progress,
                cover = cover,
                isPlaying = isPlaying,
                isLoading = isLoading,
                onClick = {
                    navController.navigate(PlayerRoute) {
                        launchSingleTop = true
                    }
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
            icon = {
                Icon(
                    imageVector = if (isHistorySelected) Icons.Filled.History else Icons.Outlined.History,
                    contentDescription = null
                )
            },
            colors = itemColors,
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
            icon = {
                Icon(
                    imageVector = if (isSettingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("设置") }
        )
    }
}
