package cn.debubu.tingbili

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.debubu.tingbili.core.media.PlaybackConnection
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import cn.debubu.tingbili.navigation.AppRootNavHost
import cn.debubu.tingbili.navigation.BottomNavWithCenterPlayer
import cn.debubu.tingbili.navigation.MainTabsRoute
import cn.debubu.tingbili.navigation.MainViewModel
import cn.debubu.tingbili.navigation.PlayerRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main entry with TingBiliTheme + Navigation Compose 2.8 type-safe.
 * Phone: Scaffold + NavigationBar (4 tabs + 贴底 mini 条 via BottomNavWithCenterPlayer).
 * Tablet/wide: explicit mapping to NavigationRail (Compact -> NavigationBar).
 *         mini player remains centered floating at bottom.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playbackConnection: PlaybackConnection

    // Activity 作用域：setContent 里的 hiltViewModel() 拿到的是同一个实例
    private val mainViewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先装启动屏（super 之前），决策完成前不撤：第一帧直接落在正确 Tab，无跳变、无白屏
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { mainViewModel.startTab.value == null }
        playbackConnection.connect()
        // 通知权限延到首 Tab 落定后再要：onCreate 直接弹框会盖住首帧，
        // 且 MediaSession 通知在起播前用不上
        lifecycleScope.launch {
            mainViewModel.startTab.filterNotNull().first()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val customThemeColor by mainViewModel.customThemeColor.collectAsStateWithLifecycle()
            val darkMode by mainViewModel.darkMode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (darkMode) {
                cn.debubu.tingbili.core.data.model.DarkMode.DARK -> true
                cn.debubu.tingbili.core.data.model.DarkMode.LIGHT -> false
                else -> systemDark
            }
            TingBiliTheme(
                darkTheme = darkTheme,
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
    // null = 首 Tab 还没定：什么都不画，系统启动屏继续盖着
    val startTab = mainViewModel.startTab.collectAsStateWithLifecycle().value ?: return
    val navController = rememberNavController()
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
    val inMainTabs = cn.debubu.tingbili.navigation.isMainTabDestination(destination)

    if (layoutType == NavigationSuiteType.NavigationBar) {
        // 手机：Scaffold 与 NavHost 常驻，底栏只在主页 Tab 显示。
        // 注意：NavHost 绝不能放在 inMainTabs 的 if/else 两分支里各调一次——
        // 那样每次进出二级页都会重建 NavHost，内部 AnimatedContent 丢掉旧状态，
        // 转场动画会被直接跳过（之前“看不到动画”就是这个原因）。
        val miniPlayerVisibility = cn.debubu.tingbili.navigation.rememberMiniPlayerVisibility()
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(miniPlayerVisibility.connection),
            bottomBar = {
                if (inMainTabs) {
                    BottomNavWithCenterPlayer(navController, visibility = miniPlayerVisibility)
                }
            }
        ) { innerPadding ->
            // 沉浸式状态栏：顶部不预留安全区，由各页内容顶到状态栏下、顶栏自行 statusBarsPadding 避让图标
            val layoutDirection = LocalLayoutDirection.current
            AppRootNavHost(
                navController = navController,
                startTab = startTab,
                mainViewModel = mainViewModel,
                // fillMaxSize 在先：mini 条收起导致 innerPadding.bottom 变小时，
                // 内容仍填满多出的空间，不露 Scaffold 底色黑条
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        end = innerPadding.calculateEndPadding(layoutDirection),
                        bottom = innerPadding.calculateBottomPadding()
                    )
            )
        }
    } else {
        // 平板：壳常驻（Rail/悬浮 mini 只在主页 Tab 显示，壳内部按目的地切换），
        // NavHost 调用点唯一，进出二级页不重建，转场才跑得起来
        cn.debubu.tingbili.navigation.MainTabsScaffold(navController = navController, startTab = startTab)
    }
}

