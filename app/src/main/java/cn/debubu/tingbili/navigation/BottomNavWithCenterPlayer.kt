package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.ui.HomeScrollTopBus
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(
    val playerManager: PlayerManager,
    prefs: PreferencesRepository,
    auth: AuthRepository,
    private val scrollTopBus: HomeScrollTopBus,
    private val playlistDao: PlaylistDao,
) : ViewModel() {

    /**
     * 冷启动首 Tab：本地收藏有内容进收藏，否则进推荐。
     * null = 还没查完，MainActivity 用它按住系统启动屏，第一帧直接落在正确 Tab。
     */
    private val _startTab = MutableStateFlow<Any?>(null)
    val startTab = _startTab.asStateFlow()

    /** 推荐 tab 双击回顶（事件经单例总线发给首页，见 HomeScrollTopBus） */
    fun requestHomeScrollTop() = scrollTopBus.requestScrollTop()

    init {
        // 登录态保活：进程启动时检查一次，需要续期则静默续期；失败忽略，下次启动再试
        viewModelScope.launch { auth.maintainSession() }
        viewModelScope.launch {
            _startTab.value = try {
                val count = withTimeoutOrNull(3_000) { playlistDao.countTracks() } ?: 0
                if (count > 0) PlaylistRoute else HomeRoute
            } catch (_: Exception) {
                HomeRoute
            }
        }
    }
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
    val darkMode = prefs.darkMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = cn.debubu.tingbili.core.data.model.DarkMode.FOLLOW_SYSTEM,
    )
}

@Composable
fun BottomNavWithCenterPlayer(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
    visibility: MiniPlayerVisibility = rememberMiniPlayerVisibility(),
) {
    val playerManager = viewModel.playerManager

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination

    val isHomeSelected = destination?.hierarchy?.any { it.hasRoute<HomeRoute>() } == true
    val isPlaylistSelected = destination?.hierarchy?.any { it.hasRoute<PlaylistRoute>() } == true
    val isHistorySelected = destination?.hierarchy?.any { it.hasRoute<HistoryRoute>() } == true
    val isMeSelected = destination?.hierarchy?.any { it.hasRoute<MeRoute>() } == true
    // M3 默认配色：选中指示块为 secondaryContainer 胶囊
    val itemColors = NavigationBarItemDefaults.colors()
    // 推荐 tab 双击回顶（onClick lambda 非组合作用域，helper 必须在外层调）
    val homeTabClick = rememberHomeTabClick(navController, isHomeSelected, viewModel)

    Column {
        // 进度 tick（500ms）只重组这个槽位，底栏 4 个 Tab 不再跟着抖
        MiniPlayerSlot(
            playerManager = playerManager,
            navController = navController,
            visibility = visibility,
        )
        NavigationBar {
        NavigationBarItem(
            selected = isHomeSelected,
            onClick = homeTabClick,
            icon = {
                Icon(
                    imageVector = if (isHomeSelected) Icons.Filled.Explore else Icons.Outlined.Explore,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("推荐") }
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
            selected = isMeSelected,
            onClick = {
                navController.navigate(MeRoute) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                }
            },
            icon = {
                Icon(
                    imageVector = if (isMeSelected) Icons.Filled.Person else Icons.Outlined.Person,
                    contentDescription = null
                )
            },
            colors = itemColors,
            label = { Text("我的") }
        )
        }
    }
}

/**
 * mini 条独立槽位：整 [PlaybackState] 订阅下沉到这里。
 * position 每 500ms 推一次，之前订阅在底栏顶层，4 个 Tab 图标每秒重测；
 * 现在 tick 只重组这一个槽位。
 */
@Composable
private fun MiniPlayerSlot(
    playerManager: PlayerManager,
    navController: NavController,
    visibility: MiniPlayerVisibility,
) {
    val state by playerManager.state.collectAsStateWithLifecycle()
    val track = state.currentTrack
    val trackKey = track?.let { "${it.bvid}:${it.cid}" }
    LaunchedEffect(trackKey) { visibility.onTrackChanged(trackKey) }
    if (track == null || trackKey == null) return
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    MiniPlayerBar(
        track = track,
        isPlaying = state.isPlaying,
        isLoading = state.isLoading,
        onToggle = { playerManager.toggle() },
        onOpen = {
            navController.navigate(PlayerRoute) { launchSingleTop = true }
        },
        progress = progress,
        visible = visibility.isVisible(trackKey),
        onDismiss = { visibility.dismiss(trackKey) },
    )
}
