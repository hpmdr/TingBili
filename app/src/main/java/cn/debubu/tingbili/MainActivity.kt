package cn.debubu.tingbili

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.debubu.tingbili.core.media.PlaybackConnection
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import cn.debubu.tingbili.navigation.AppRootNavHost
import cn.debubu.tingbili.navigation.BottomNavWithCenterPlayer
import cn.debubu.tingbili.navigation.CircularMiniPlayer
import cn.debubu.tingbili.navigation.MainTabsRoute
import cn.debubu.tingbili.navigation.MainViewModel
import cn.debubu.tingbili.navigation.PlayerRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry with TingBiliTheme + Navigation Compose 2.8 type-safe.
 * Phone: Scaffold + NavigationBar (4 tabs + centered CircularMiniPlayer via BottomNavWithCenterPlayer).
 * Tablet: NavigationSuiteScaffold auto-switches to NavigationRail (via calculateFromAdaptiveInfo),
 *         mini player remains centered floating at bottom.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playbackConnection: PlaybackConnection

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playbackConnection.connect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination

    if (layoutType == NavigationSuiteType.NavigationBar) {
        // 手机：主页 Tab 带底栏 + 圆形 mini；独立页全屏（由 MainTabsScaffold/AppRootNavHost 决定）
        val inTabs = destination?.hierarchy?.any {
            it.hasRoute(cn.debubu.tingbili.navigation.HomeRoute::class) ||
            it.hasRoute(cn.debubu.tingbili.navigation.PlaylistRoute::class) ||
            it.hasRoute(cn.debubu.tingbili.navigation.HistoryRoute::class) ||
            it.hasRoute(cn.debubu.tingbili.navigation.SettingsRoute::class) ||
            it.hasRoute(MainTabsRoute::class)
        } ?: true
        if (inTabs) {
            Scaffold(
                bottomBar = { BottomNavWithCenterPlayer(navController) }
            ) { innerPadding ->
                AppRootNavHost(navController = navController, modifier = Modifier.padding(innerPadding))
            }
        } else {
            // 独立全屏页：无底栏
            AppRootNavHost(navController = navController, modifier = Modifier.fillMaxSize())
        }
    } else {
        // 平板：NavigationRail + 悬浮 mini，仅主页 Tab 显示
        cn.debubu.tingbili.navigation.MainTabsScaffold(navController = navController)
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
    val isLoading = state.isLoading

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularMiniPlayer(
            progress = progress,
            cover = cover,
            isPlaying = isPlaying,
            isLoading = isLoading,
            onClick = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
        )
    }
}
