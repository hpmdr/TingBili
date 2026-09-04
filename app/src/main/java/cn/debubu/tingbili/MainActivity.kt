package cn.debubu.tingbili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import cn.debubu.tingbili.navigation.AppNavHost
import cn.debubu.tingbili.navigation.BottomNavWithCenterPlayer
import cn.debubu.tingbili.navigation.CircularMiniPlayer
import cn.debubu.tingbili.navigation.HistoryRoute
import cn.debubu.tingbili.navigation.HomeRoute
import cn.debubu.tingbili.navigation.MainViewModel
import cn.debubu.tingbili.navigation.PlayerRoute
import cn.debubu.tingbili.navigation.PlaylistRoute
import cn.debubu.tingbili.navigation.SettingsRoute
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main entry with TingBiliTheme + Navigation Compose 2.8 type-safe.
 * Phone: Scaffold + NavigationBar (4 tabs + centered CircularMiniPlayer via BottomNavWithCenterPlayer).
 * Tablet: NavigationSuiteScaffold auto-switches to NavigationRail (via calculateFromAdaptiveInfo),
 *         mini player remains centered floating at bottom.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TingBiliTheme {
                AdaptiveMainScaffold()
            }
        }
    }
}

@Composable
private fun AdaptiveMainScaffold() {
    val navController = rememberNavController()
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    // Hoist navigation state outside NavigationSuiteScaffold's non-composable scope
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination
    // Use hasRoute (Companion) with hierarchy check for robustness, fallback to string if needed
    val isHomeSelected = destination?.hierarchy?.any { it.hasRoute<HomeRoute>() } == true
    val isPlaylistSelected = destination?.hierarchy?.any { it.hasRoute<PlaylistRoute>() } == true
    val isHistorySelected = destination?.hierarchy?.any { it.hasRoute<HistoryRoute>() } == true
    val isSettingsSelected = destination?.hierarchy?.any { it.hasRoute<SettingsRoute>() } == true

    if (layoutType == NavigationSuiteType.NavigationBar) {
        // Phone: bottom bar with 4 tabs + centered circular mini player inside NavigationBar
        Scaffold(
            bottomBar = { BottomNavWithCenterPlayer(navController) }
        ) { innerPadding ->
            AppNavHost(
                navController = navController,
                innerPadding = innerPadding
            )
        }
    } else {
        // Tablet / expanded: side NavigationRail via NavigationSuiteScaffold + floating centered mini player
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                item(
                    selected = isHomeSelected,
                    onClick = {
                        navController.navigate(HomeRoute) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("首页") }
                )
                item(
                    selected = isPlaylistSelected,
                    onClick = {
                        navController.navigate(PlaylistRoute) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("歌单") }
                )
                item(
                    selected = isHistorySelected,
                    onClick = {
                        navController.navigate(HistoryRoute) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("历史") }
                )
                item(
                    selected = isSettingsSelected,
                    onClick = {
                        navController.navigate(SettingsRoute) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            },
            layoutType = layoutType
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppNavHost(
                    navController = navController,
                    innerPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxSize()
                )
                // Floating centered circular mini player — remains centered on tablet
                FloatingCenteredMiniPlayer(
                    navController = navController,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingCenteredMiniPlayer(
    navController: androidx.navigation.NavController,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = hiltViewModel()
) {
    val playerManager = viewModel.playerManager
    val state by playerManager.state.collectAsStateWithLifecycle()
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val cover = state.currentTrack?.cover ?: ""
    val isPlaying = state.isPlaying

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularMiniPlayer(
            progress = progress,
            cover = cover,
            isPlaying = isPlaying,
            onClick = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
        )
    }
}
