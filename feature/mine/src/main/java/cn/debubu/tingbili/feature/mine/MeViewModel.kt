package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.SimpleCache
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.DarkMode
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的」页数据：
 * - 收藏数 = 歌单数；历史数 = 今日新增条数；缓存 = 音频缓存实际占用
 * - 听视频累计时长暂无数据源，未登录态头部显示引导文案
 */
@HiltViewModel
class MeViewModel @Inject constructor(
    playlistDao: PlaylistDao,
    historyDao: HistoryDao,
    private val prefs: PreferencesRepository,
    private val audioCache: SimpleCache,
    private val auth: AuthRepository
) : ViewModel() {

    val session = auth.sessionFlow

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }

    val darkMode: StateFlow<DarkMode> = prefs.darkMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), DarkMode.FOLLOW_SYSTEM
    )

    /** 设置入口动态副标题用：播放步长 / 字幕缩放 / 音质偏好 / 硬解开关 / 图片格式 / 主题模式 */
    val stepSec: StateFlow<Int> = prefs.stepSec.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 15
    )
    val subtitleScale: StateFlow<Float> = prefs.subtitleScale.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 1f
    )
    val audioQuality: StateFlow<Int> = prefs.audioQuality.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_AUDIO_QUALITY
    )
    val audioHwDecode: StateFlow<Boolean> = prefs.audioHwDecode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )
    val imageFormat: StateFlow<ImageFormat> = prefs.imageFormat.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ImageFormat.AVIF
    )
    val themeMode: StateFlow<ThemeMode> = prefs.themeMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.DEFAULT
    )

    /** 头部明暗快捷切换：当前为暗（含跟随系统且系统暗）则切浅色，否则切深色 */
    fun toggleDarkMode(currentEffectiveDark: Boolean) {
        viewModelScope.launch {
            prefs.setDarkMode(if (currentEffectiveDark) DarkMode.LIGHT else DarkMode.DARK)
        }
    }

    val playlistCount: StateFlow<Int> = playlistDao.observePlaylists()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** 本地累计收听时长（ms），无需登录 */
    val totalListenMs: StateFlow<Long> = historyDao.observeTotalListenMs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** 今日零点（VM 存续期只算一次，跨天重进页面即刷新；DB 侧 COUNT，不拉全表） */
    private val todayStart: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val historyTodayCount: StateFlow<Int> = historyDao.observeTodayCount(todayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        refreshCache()
    }

    fun refreshCache() {
        viewModelScope.launch {
            _cacheBytes.value = try {
                audioCache.cacheSpace
            } catch (_: Exception) { 0L }
        }
    }
}
