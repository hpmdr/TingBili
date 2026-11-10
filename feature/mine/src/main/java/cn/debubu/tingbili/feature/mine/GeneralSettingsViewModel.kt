package cn.debubu.tingbili.feature.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.BuildConfig
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
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

/** 「其他设置」：B 站图片格式、首页推荐词、版本更新。 */
@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val update: UpdateDataSource,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val imageFormat: StateFlow<ImageFormat> = prefs.imageFormat.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ImageFormat.AVIF
    )

    fun setImageFormat(v: ImageFormat) {
        viewModelScope.launch { prefs.setImageFormat(v) }
    }

    /** 首页推荐词（设置页可增减，至少保留一个） */
    val homeKeywords: StateFlow<List<String>> = prefs.homeKeywords.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_HOME_KEYWORDS
    )

    fun addHomeKeyword(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        viewModelScope.launch {
            val cur = homeKeywords.value
            if (k !in cur) prefs.setHomeKeywords(cur + k)
        }
    }

    fun removeHomeKeyword(keyword: String) {
        viewModelScope.launch {
            val cur = homeKeywords.value
            if (cur.size <= 1) return@launch // 至少保留一个
            prefs.setHomeKeywords(cur - keyword)
        }
    }

    /** 推荐词弹层使用草稿编辑，完成时一次性提交。 */
    fun setHomeKeywords(keywords: List<String>) {
        val cleaned = keywords
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .ifEmpty { listOf(PreferencesRepository.DEFAULT_HOME_KEYWORDS.first()) }
        viewModelScope.launch { prefs.setHomeKeywords(cleaned) }
    }
    // ── 版本更新（只比发布时间，不比 tag 字符串）─────────────────────

    val autoCheckUpdate: StateFlow<Boolean> = prefs.autoCheckUpdate.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
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

    /** Snackbar 展示后复位 UpToDate/Failed；弹窗走 dismissUpdate。 */
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
