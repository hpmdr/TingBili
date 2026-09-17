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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.debubu.tingbili.core.media.PlaybackConnection
import cn.debubu.tingbili.core.ui.LocalImageFormat
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import cn.debubu.tingbili.navigation.AppRootNavHost
import cn.debubu.tingbili.navigation.BottomNavWithCenterPlayer
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
 * Phone: Scaffold + NavigationBar (4 tabs + floating MiniPlayerBar via BottomNavWithCenterPlayer).
 * Tablet/wide: explicit mapping to NavigationRail (Compact -> NavigationBar).
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
        // HyperOS 上仅靠 enforcement 会被画黑条：像哔哩哔哩一样走经典全屏标记，
        // 让窗口自己绘制系统栏背景（透明），内容透到状态栏下
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val customThemeColor by mainViewModel.customThemeColor.collectAsStateWithLifecycle()
            TingBiliTheme(
                themeMode = themeMode,
                customThemeColorArgb = customThemeColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AdaptiveMainScaffold(mainViewModel)
                }
            }
        }
    }
}

@Composable
private fun AdaptiveMainScaffold(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    val imageFormat by mainViewModel.imageFormat.collectAsStateWithLifecycle()
    val adaptiveInfo = currentWindowAdaptiveInfo()
    // 宽 >= 600dp 一律 Rail：实测本机横屏（约 904dp）官方默认映射仍返回
    // NavigationBar，且 Expanded 会走向抽屉——手机横屏要的是左侧 Rail。
    val layoutType = if (!adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(600)) {
        NavigationSuiteType.NavigationBar
    } else {
        NavigationSuiteType.NavigationRail
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination
    val inMainTabs = destination?.hierarchy?.any {
        it.hasRoute(cn.debubu.tingbili.navigation.HomeRoute::class) ||
        it.hasRoute(cn.debubu.tingbili.navigation.PlaylistRoute::class) ||
        it.hasRoute(cn.debubu.tingbili.navigation.HistoryRoute::class) ||
        it.hasRoute(cn.debubu.tingbili.navigation.SettingsRoute::class) ||
        it.hasRoute(MainTabsRoute::class)
    } ?: true

    CompositionLocalProvider(LocalImageFormat provides imageFormat) {
    if (layoutType == NavigationSuiteType.NavigationBar) {
        // 手机：主页 Tab 带底栏 + 悬浮 mini 条；独立页全屏（由 MainTabsScaffold/AppRootNavHost 决定）
        if (inMainTabs) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = { BottomNavWithCenterPlayer(navController) }
            ) { innerPadding ->
                // 沉浸式状态栏：顶部不预留安全区，由各页内容顶到状态栏下、顶栏自行 statusBarsPadding 避让图标
                val layoutDirection = LocalLayoutDirection.current
                AppRootNavHost(
                    navController = navController,
                    modifier = Modifier.padding(
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        end = innerPadding.calculateEndPadding(layoutDirection),
                        bottom = innerPadding.calculateBottomPadding()
                    )
                )
            }
        } else {
            // 独立全屏页：无底栏
            AppRootNavHost(navController = navController, modifier = Modifier.fillMaxSize())
        }
    } else {
        // 平板：NavigationRail + 悬浮 mini，仅主页 Tab 显示
        if (inMainTabs) {
            cn.debubu.tingbili.navigation.MainTabsScaffold(navController = navController)
        } else {
            AppRootNavHost(navController = navController, modifier = Modifier.fillMaxSize())
        }
    }
    }
}

