package cn.debubu.tingbili.navigation

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.BuildConfig
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.data.model.toTrack
import cn.debubu.tingbili.core.data.network.NetworkMonitor
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import cn.debubu.tingbili.core.data.update.AutoUpdateGate
import cn.debubu.tingbili.core.data.update.UpdateChecker
import cn.debubu.tingbili.core.data.update.UpdateDataSource
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.core.ui.HomeScrollTopBus
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    val playerManager: PlayerManager,
    private val networkMonitor: NetworkMonitor,
    private val prefs: PreferencesRepository,
    auth: AuthRepository,
    private val scrollTopBus: HomeScrollTopBus,
    private val favoritesRepository: FavoritesRepository,
    private val update: UpdateDataSource,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * 冷启动首 Tab：本地收藏有内容进收藏，否则进推荐。
     * null = 还没查完，MainActivity 用它按住系统启动屏，第一帧直接落在正确 Tab。
     */
    private val _startTab = MutableStateFlow<Any?>(null)
    val startTab = _startTab.asStateFlow()

    /**
     * 通知点击直达播放页（P1）：MainActivity 收到 intent 后置位，导航层消费后复位。
     * 走 VM 而不是把布尔一路当 Composable 参数传：NavHost 的 destination lambda 会捕获旧值，
     * 导致「冷启动能跳、运行中再点不跳」（实测踩过）。
     */
    private val _openPlayer = MutableStateFlow(false)
    val openPlayerRequest = _openPlayer.asStateFlow()

    /**
     * 启动自动检查出的新版（null = 无事）。弹窗由 MainTabsScaffold 渲染，
     * 下载走浏览器直链（免站内安装链路），见 [UpdateChecker]。
     */
    data class UpdatePrompt(
        val tag: String,
        val body: String,
        val apkUrl: String?,
        val publishedSec: Long,
    )

    private val _pendingUpdate = MutableStateFlow<UpdatePrompt?>(null)
    val pendingUpdate = _pendingUpdate.asStateFlow()

    fun dismissUpdate() {
        _pendingUpdate.value = null
    }

    /** "不再提醒此版"：只记发布时间，tag 重发（时间变）照弹。 */
    fun skipUpdate() {
        viewModelScope.launch {
            _pendingUpdate.value?.let { prefs.setSkippedReleaseSec(it.publishedSec) }
            _pendingUpdate.value = null
        }
    }

    /**
     * 启动自动检查：debug 跳过（本地 BUILD_TIME 永远最新），每 12 小时最多一次，
     * 关掉开关/用户跳过此版/失败都静默（手动入口在其他设置）。
     */
    private suspend fun autoCheckUpdate() {
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val now = System.currentTimeMillis()
        if (!AutoUpdateGate.shouldFetch(
                debuggable = debuggable,
                autoCheckEnabled = prefs.autoCheckUpdate.first(),
                nowMs = now,
                lastCheckMs = prefs.lastUpdateCheckMs.first(),
            )
        ) return
        prefs.setLastUpdateCheckMs(now)
        val rel = update.fetchLatest().getOrNull() ?: return
        val remoteSec = UpdateChecker.publishedAtSec(rel.publishedAt)
        if (!AutoUpdateGate.shouldPrompt(
                skippedReleaseSec = prefs.skippedReleaseSec.first(),
                localVersion = localVersionName(),
                remoteTag = rel.tag,
                remotePublishedSec = remoteSec,
                localBuildSec = BuildConfig.BUILD_TIME,
            )
        ) return
        _pendingUpdate.value = UpdatePrompt(
            tag = rel.tag,
            body = rel.body.orEmpty(),
            apkUrl = UpdateChecker.pickApkUrl(rel.assets),
            publishedSec = remoteSec ?: 0L,
        )
    }

    /** 本地 versionName；取不到按空串（tag 不同即视为新版，交给时间条件兜底）。 */
    private fun localVersionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /**
     * 全局在线状态（透传单例 NetworkMonitor，不断包一层 stateIn：
     * 本身已是带现值的 StateFlow，scaffold 直接订阅即可，翻转频率极低）。
     */
    val isOnline: kotlinx.coroutines.flow.StateFlow<Boolean> = networkMonitor.isOnline

    /** 回到前台时校准一次在线状态：NetworkCallback 可能被省电丢弃，回前台重读兜底（微秒级内存查询）。 */
    fun refreshNetworkState() = networkMonitor.refresh()

    fun requestOpenPlayer() { _openPlayer.value = true }

    fun consumeOpenPlayer() { _openPlayer.value = false }

    /** 推荐 tab 双击回顶（事件经单例总线发给首页，见 HomeScrollTopBus） */
    fun requestHomeScrollTop() = scrollTopBus.requestScrollTop()

    /**
     * 冷启动落地 tab：先读偏好里的「曾经有收藏」标记（一个布尔，微秒级）；
     * 只有覆盖安装后的首启没有该标记时，才退回查一次 Room 并回填——之后启动不再碰 DB（REVIEW_REPORT B-02）。
     * 查库超时（慢存储/首次 openDatabase）则不写标记，下次启动重试，避免把 false 写死。
     */
    private suspend fun resolveStartTab(): Any {
        val cached = withTimeoutOrNull(FAVORITE_FLAG_READ_TIMEOUT_MS) { prefs.hasFavoriteEverOrNull() }
        if (cached != null) return if (cached) FavoritesRoute else HomeRoute
        val hasFavorite = withTimeoutOrNull(FAVORITE_BACKFILL_TIMEOUT_MS) { favoritesRepository.hasAnyFavorite() }
        if (hasFavorite != null) prefs.setHasFavoriteEver(hasFavorite)
        return if (hasFavorite == true) FavoritesRoute else HomeRoute
    }

    private companion object {
        /** 读一个布尔，正常情况下 1ms 内返回；给足余量避免 DataStore 首读抖动误判 */
        const val FAVORITE_FLAG_READ_TIMEOUT_MS = 300L

        /** 仅覆盖安装后的首启会走这里（查一次 Room 并回填），之后不再进入 */
        const val FAVORITE_BACKFILL_TIMEOUT_MS = 800L
    }

    init {
        // 登录态保活：进程启动时检查一次，需要续期则静默续期；失败忽略，下次启动再试
        viewModelScope.launch { auth.maintainSession() }
        viewModelScope.launch {
            _startTab.value = try {
                resolveStartTab()
            } catch (_: Exception) {
                HomeRoute
            }
        }
        viewModelScope.launch { autoCheckUpdate() }
        // 播放列表跟随来源收藏夹：夹内增删 P / 改名 / 拖拽排序后，播放页章节实时对齐
        viewModelScope.launch {
            playerManager.state
                .map { it.sourceFolderId }
                .distinctUntilChanged()
                .flatMapLatest { folderId ->
                    if (folderId == null) {
                        flowOf(null)
                    } else {
                        combine(
                            favoritesRepository.observeTracks(folderId),
                            favoritesRepository.observeFolder(folderId),
                        ) { tracks, folder -> FolderSnapshot(folderId, folder?.name, tracks) }
                    }
                }
                .collect { snapshot ->
                    if (snapshot != null) {
                        playerManager.syncFolderQueue(
                            folderId = snapshot.folderId,
                            newTracks = snapshot.tracks.map { it.toTrack() },
                            folderName = snapshot.name,
                        )
                    }
                }
        }
    }

    private data class FolderSnapshot(
        val folderId: Long,
        val name: String?,
        val tracks: List<cn.debubu.tingbili.core.data.db.FavoriteTrackEntity>,
    )
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
