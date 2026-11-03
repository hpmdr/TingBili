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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.debubu.tingbili.core.media.PlaybackConnection
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import cn.debubu.tingbili.navigation.AppRootNavHost
import cn.debubu.tingbili.navigation.MainBottomNav
import cn.debubu.tingbili.navigation.MiniPlayerOverlay
import cn.debubu.tingbili.navigation.MainTabsRoute
import cn.debubu.tingbili.navigation.MainViewModel
import cn.debubu.tingbili.navigation.LoginRoute
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
 * Phone: Scaffold + NavigationBar (4 tabs) + 悬浮 mini 条盖在内容上（列表从后面滚过）。
 * Tablet/wide: explicit mapping to NavigationRail (Compact -> NavigationBar).
 *         mini player remains centered floating at bottom.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playbackConnection: PlaybackConnection
    @Inject lateinit var playerManager: PlayerManager

    // Activity 作用域：setContent 里的 hiltViewModel() 拿到的是同一个实例
    private val mainViewModel: MainViewModel by viewModels()

    // 通知权限被拒后必须给一次性提示（REVIEW_REPORT A-03）：
    // 否则媒体通知、锁屏卡片、耳机线控会静默消失，用户只会觉得「播放坏了」。
    private var notificationDenied by mutableStateOf(false)

    /** 每进程只提示一次：播放开始时若通知被关，弹一次说明就够，反复弹会烦人 */
    private var notificationHintShown = false


    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) notificationDenied = true
        }

    /**
     * 锁屏/深度睡眠回来后唯一的可靠恢复点：先按播放器位置对表，再决定是否重建音频路径。
     * 必须在 UI 开始收数据之前做，否则第一帧会用冻结前的旧位置渲染字幕。
     */
    override fun onStart() {
        super.onStart()
        playerManager.onUiVisible()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenPlayerExtra(intent)
    }

    private fun consumeOpenPlayerExtra(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(cn.debubu.tingbili.core.media.TingBiliPlaybackService.EXTRA_OPEN_PLAYER, false) == true) {
            mainViewModel.requestOpenPlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先装启动屏（super 之前），决策完成前不撤：第一帧直接落在正确 Tab，无跳变、无白屏
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { mainViewModel.startTab.value == null }
        playbackConnection.connect()
        consumeOpenPlayerExtra(intent)
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
        // 用户可能没在申请时拒绝，而是事后去系统设置关掉通知；播放开始时补一次检查（A-03 最佳实践）。
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                playerManager.state
                    .map { it.isPlaying }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect {
                        if (notificationHintShown) return@collect
                        val enabled = androidx.core.app.NotificationManagerCompat.from(this@MainActivity)
                            .areNotificationsEnabled()
                        if (!enabled) {
                            notificationHintShown = true
                            notificationDenied = true
                        }
                    }
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
            val darkThemeStyle by mainViewModel.darkThemeStyle.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (darkMode) {
                cn.debubu.tingbili.core.data.model.DarkMode.DARK -> true
                cn.debubu.tingbili.core.data.model.DarkMode.LIGHT -> false
                else -> systemDark
            }
            TingBiliTheme(
                darkTheme = darkTheme,
                themeMode = themeMode,
                customThemeColorArgb = customThemeColor,
                darkAmoled = darkThemeStyle == cn.debubu.tingbili.core.data.model.DarkThemeStyle.PURE_BLACK
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AdaptiveMainScaffold(mainViewModel)
                }
                if (notificationDenied) {
                    NotificationDeniedDialog(onDismiss = { notificationDenied = false })
                }
            }
        }
    }
}

@Composable
private fun AdaptiveMainScaffold(mainViewModel: MainViewModel) {
    // null = 首 Tab 还没定：先不画内容，系统启动屏继续盖着。
    // 但 pagerState 必须先创建：否则 Activity 因 IME/弹窗重建时，页面会回到首 Tab。
    val startTab = mainViewModel.startTab.collectAsStateWithLifecycle().value
    val navController = rememberNavController()
    // 4 Tab 的横向分页：与底栏共享同一个 state，点击/左右滑都落到这里
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = cn.debubu.tingbili.navigation.mainTabPageOf(
            startTab ?: cn.debubu.tingbili.navigation.HomeRoute
        )
    ) { 4 }
    var startTabApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startTab) {
        val tab = startTab ?: return@LaunchedEffect
        if (!startTabApplied) {
            startTabApplied = true
            val page = cn.debubu.tingbili.navigation.mainTabPageOf(tab)
            if (pagerState.currentPage != page) pagerState.scrollToPage(page)
        }
    }
    if (startTab == null) return
    // 全局离线横幅数据源：低频状态（只在连接翻转时变），放壳顶层订阅没问题。
    val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()
    // 回前台校准一次在线状态：NetworkCallback 可能被省电 ROM 丢弃，亮屏回来主动查一次兜底
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) mainViewModel.refreshNetworkState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
    // hierarchy 遍历 + hasRoute 每次重组都要分配；只在导航目标变化时重算（REVIEW_REPORT C-04）
    val (inMainTabs, showMiniPlayer) = remember(destination) {
        // 全局 mini：除全屏播放页/登录页外，二级页（详情、UP、设置…）也常驻，
        // 不随页面切换重建，转场时保持连续
        val inTabs = cn.debubu.tingbili.navigation.isMainTabDestination(destination)
        val showMini = destination?.hierarchy?.any {
            it.hasRoute<PlayerRoute>() || it.hasRoute<LoginRoute>()
        } != true
        inTabs to showMini
    }

    // 离线横幅盖两分支（手机 Scaffold / 平板 MainTabsScaffold），悬浮在顶部、零占位，
    // 内容不被挤压下移；放 NavHost 之外：进出二级页不重建，转场动画不受影响。
    Box(modifier = Modifier.fillMaxSize()) {
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
                    MainBottomNav(pagerState)
                }
            }
        ) { innerPadding ->
            // 沉浸式状态栏：顶部不预留安全区，由各页内容顶到状态栏下、顶栏自行 statusBarsPadding 避让图标
            val layoutDirection = LocalLayoutDirection.current
            // mini 条在整屏坐标里定位：主页 Tab 时停在底栏上沿，二级页时贴屏幕底。
            // 位置变化走动画，避免进出详情页时上下硬跳（内容 padding 仍立即生效）。
            val miniPlayerBottom by animateDpAsState(
                targetValue = if (inMainTabs) innerPadding.calculateBottomPadding() else 0.dp,
                animationSpec = tween(durationMillis = 220),
                label = "miniPlayerBottom",
            )
            Box(modifier = Modifier.fillMaxSize()) {
                AppRootNavHost(
                    navController = navController,
                    pagerState = pagerState,
                    mainViewModel = mainViewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection),
                            bottom = innerPadding.calculateBottomPadding()
                        ),
                )
                // 悬浮 mini：盖在列表上、不占位，列表从它后面滚过；
                // 显隐只做位移+淡出，内边距全程不动，滑到底不弹
                if (showMiniPlayer) {
                    MiniPlayerOverlay(
                        navController = navController,
                        visibility = miniPlayerVisibility,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = miniPlayerBottom),
                    )
                }
            }
        }
    } else {
        // 平板：壳常驻（Rail/悬浮 mini 只在主页 Tab 显示，壳内部按目的地切换），
        // NavHost 调用点唯一，进出二级页不重建，转场才跑得起来
        cn.debubu.tingbili.navigation.MainTabsScaffold(
            navController = navController,
            pagerState = pagerState,
        )
    }
        // 悬浮离线横幅：微信式顶部常驻提示，盖在内容上不占布局；statusBarsPadding 避开状态栏图标
        AnimatedVisibility(
            visible = !isOnline,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            OfflineBanner(modifier = Modifier.statusBarsPadding())
        }
    }
}

/**
 * 全局离线横幅：非阻塞常驻提示（M3 里持久性应用级警告归 Banner，不归 Dialog）。
 * 只在断网时出现，恢复自动消失；不抢焦点、不挡操作，半夜场景零打扰。
 */
@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "网络已断开，仅可播放已缓存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * 通知权限被拒的说明与二次引导（REVIEW_REPORT A-03）：
 * 不弹「再问一次」系统框（用户已明确拒绝，系统也可能不再展示），只解释后果并给「去设置」。
 */
@Composable
private fun NotificationDeniedDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通知权限已关闭") },
        text = {
            Text(
                "关闭后，播放控制面板不会出现在通知栏和锁屏上，耳机线控也可能无响应。" +
                    "播放本身不受影响，可在系统设置的「通知」里重新打开。",
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                },
            ) { Text("去设置") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
