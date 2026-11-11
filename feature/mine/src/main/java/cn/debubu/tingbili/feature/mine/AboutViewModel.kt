package cn.debubu.tingbili.feature.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.BuildConfig
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.update.AutoUpdateGate
import cn.debubu.tingbili.core.data.update.UpdateChecker
import cn.debubu.tingbili.core.data.update.UpdateDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「关于」页：版本信息 + 手动/自动更新（更新逻辑从 GeneralSettingsViewModel 迁入）。 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val update: UpdateDataSource,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** 版本名 + 版本号（如 v0.3.0 (20)）；取不到时退回占位。 */
    val versionLabel: String = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "v${info.versionName ?: "1.0"} (${info.longVersionCode})"
    } catch (_: Exception) {
        "v1.0"
    }

    val autoCheckUpdate: StateFlow<Boolean> = prefs.autoCheckUpdate.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    /** 上次检查时间（ms，0=从未检查）；关于页显示具体时间。 */
    val lastCheckMs: StateFlow<Long> = prefs.lastUpdateCheckMs.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0L
    )

    fun setAutoCheckUpdate(v: Boolean) {
        viewModelScope.launch { prefs.setAutoCheckUpdate(v) }
    }

    sealed interface UpdateUiState {
        data object Idle : UpdateUiState
        data object Checking : UpdateUiState
        data object UpToDate : UpdateUiState
        data object Failed : UpdateUiState
        data class Available(
            val tag: String,
            val body: String,
            val apkUrl: String?,
            val publishedSec: Long,
        ) : UpdateUiState
    }

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState = _updateState.asStateFlow()

    /** 手动检查：失败/已是最新由界面 Snackbar 提示；有新版弹 [UpdateUiState.Available]。 */
    fun checkUpdate() {
        if (_updateState.value == UpdateUiState.Checking) return
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            val now = System.currentTimeMillis()
            val rel = update.fetchLatest().getOrNull()
            prefs.setLastUpdateCheckMs(now)
            if (rel == null) {
                _updateState.value = UpdateUiState.Failed
                return@launch
            }
            val remoteSec = UpdateChecker.publishedAtSec(rel.publishedAt)
            // 手动入口也认"不再提醒此版"：否则每次点检查都把用户刚拒掉的版本弹回来。
            val skipped = prefs.skippedReleaseSec.first()
            if (!AutoUpdateGate.shouldPrompt(
                    skippedReleaseSec = skipped,
                    localVersion = localVersionName(),
                    remoteTag = rel.tag,
                    remotePublishedSec = remoteSec,
                    localBuildSec = BuildConfig.BUILD_TIME,
                )
            ) {
                _updateState.value = UpdateUiState.UpToDate
                return@launch
            }
            _updateState.value = UpdateUiState.Available(
                tag = rel.tag,
                body = rel.body.orEmpty(),
                apkUrl = UpdateChecker.pickApkUrl(rel.assets),
                publishedSec = remoteSec ?: 0L,
            )
        }
    }

    /** 本地 versionName；取不到按空串（tag 不同即视为新版，交给时间条件兜底）。 */
    private fun localVersionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** "不再提醒此版"：只记发布时间，tag 重发（时间变）照弹。 */
    fun skipRelease() {
        val cur = _updateState.value as? UpdateUiState.Available ?: return
        viewModelScope.launch {
            prefs.setSkippedReleaseSec(cur.publishedSec)
            _updateState.value = UpdateUiState.Idle
        }
    }

    /** Snackbar 展示后复位 UpToDate/Failed；弹窗走 [dismissUpdate]。 */
    fun consumeUpdateMessage() {
        val cur = _updateState.value
        if (cur == UpdateUiState.UpToDate || cur == UpdateUiState.Failed) {
            _updateState.value = UpdateUiState.Idle
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateUiState.Idle
    }
}
