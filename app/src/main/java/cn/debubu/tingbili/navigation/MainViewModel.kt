package cn.debubu.tingbili.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.core.ui.HomeScrollTopBus
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val darkThemeStyle = prefs.darkThemeStyle.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = cn.debubu.tingbili.core.data.model.DarkThemeStyle.DARK_GRAY,
    )
}
