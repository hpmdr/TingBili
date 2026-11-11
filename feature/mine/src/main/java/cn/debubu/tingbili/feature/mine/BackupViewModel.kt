package cn.debubu.tingbili.feature.mine

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.backup.BackupCodec
import cn.debubu.tingbili.core.data.backup.BackupPayload
import cn.debubu.tingbili.core.data.backup.BackupPreview
import cn.debubu.tingbili.core.data.backup.BackupStore
import cn.debubu.tingbili.core.data.backup.ImportMode
import cn.debubu.tingbili.core.data.backup.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「其他设置 → 备份与恢复」：导出/解析预览/合并或替换导入。 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val store: BackupStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object Working : UiState
        data class Exported(val fileName: String) : UiState
        data class Preview(val payload: BackupPayload, val preview: BackupPreview) : UiState
        data class Imported(val result: ImportResult) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state = _state.asStateFlow()

    fun export(uri: Uri, includeAuth: Boolean) {
        if (_state.value == UiState.Working) return
        viewModelScope.launch {
            _state.value = UiState.Working
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val payload = store.export(includeAuth)
                    val text = BackupCodec.encode(payload)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
            }
            _state.value = result.fold(
                onSuccess = { UiState.Exported(uri.lastPathSegment ?: "备份文件") },
                onFailure = { UiState.Error(it.message ?: "导出失败") },
            )
        }
    }

    fun parseImport(uri: Uri) {
        if (_state.value == UiState.Working) return
        viewModelScope.launch {
            _state.value = UiState.Working
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("无法读取所选文件")
                    if (text.length > MAX_FILE_CHARS) error("备份文件过大")
                    BackupCodec.decode(text).getOrThrow()
                }
            }
            _state.value = result.fold(
                onSuccess = { UiState.Preview(it, it.toPreview()) },
                onFailure = { UiState.Error(it.message ?: "导入失败") },
            )
        }
    }

    fun confirmImport(mode: ImportMode) {
        val preview = _state.value as? UiState.Preview ?: return
        viewModelScope.launch {
            _state.value = UiState.Working
            val result = runCatching { store.import(preview.payload, mode) }
            _state.value = result.fold(
                onSuccess = { UiState.Imported(it) },
                onFailure = { UiState.Error(it.message ?: "导入失败，本地数据未变") },
            )
        }
    }

    fun dismissPreview() {
        _state.value = UiState.Idle
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    private fun BackupPayload.toPreview(): BackupPreview {
        val data = data
        val settings = data.settings
        val settingsCount = listOf(
            settings.stepSec,
            settings.repeatMode,
            settings.randomPlayMode,
            settings.speed,
            settings.subtitleScale,
            settings.audioHwDecode,
            settings.audioOffload,
            settings.audioQuality,
            settings.sleepFinishChapter,
            settings.sleepLastMinutes,
            settings.themeMode,
            settings.customThemeColor,
            settings.darkMode,
            settings.darkThemeStyle,
            settings.imageFormat,
            settings.homeKeywords,
            settings.cacheMaxMb,
            settings.autoCheckUpdate,
            settings.searchHistory,
        ).count { it != null }
        return BackupPreview(
            appVersion = appVersion.ifBlank { "未知" },
            exportedAt = exportedAt,
            includesAuth = includesAuth || data.auth != null,
            folderCount = data.folders.size,
            trackCount = data.tracks.size,
            bvFavoriteCount = data.bvFavorites.size,
            historyCount = data.history.size,
            searchHistoryCount = settings.searchHistory?.size ?: 0,
            settingsCount = settingsCount,
        )
    }

    private companion object {
        /** JSON 字符数上限，防止误选超大文件把内存打满。 */
        const val MAX_FILE_CHARS = 10 * 1024 * 1024
    }
}
