package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.cache.CacheManager
import cn.debubu.tingbili.core.data.cache.CacheStats
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「存储设置」：用量统计与缓存清理（音频 / 图片 / 字幕分别统计清理）+ 音频缓存上限。 */
@HiltViewModel
class StorageSettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val cacheManager: CacheManager,
    private val player: PlayerManager,
) : ViewModel() {

    val cacheMaxMb: StateFlow<Int> = prefs.cacheMaxMb.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PreferencesRepository.DEFAULT_CACHE_MAX_MB
    )

    fun setCacheMaxMb(v: Int) {
        viewModelScope.launch { prefs.setCacheMaxMb(v) }
    }

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    /** 一次性提示（Snackbar）：清理结果，展示后即消费 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() {
        _notice.value = null
    }

    init {
        refreshCacheStats()
    }

    fun refreshCacheStats() {
        viewModelScope.launch {
            _cacheStats.value = try {
                cacheManager.stats()
            } catch (_: Exception) {
                CacheStats()
            }
        }
    }

    /** 正在播放的音频 key（`bvid:cid`，与 MediaItem.customCacheKey 一致），清理时跳过 */
    private fun playingAudioKey(): String? =
        player.state.value.currentTrack?.let { "${it.bvid}:${it.cid}" }

    fun clearAudioCache() {
        viewModelScope.launch {
            val exclude = playingAudioKey()
            val freed = try {
                cacheManager.clearAudio(exclude)
            } catch (_: Exception) {
                0L
            }
            refreshCacheStats()
            _notice.value = if (exclude != null) "已清理音频缓存 ${formatCacheSize(freed)}（正在播放的已跳过）"
            else "已清理音频缓存 ${formatCacheSize(freed)}"
        }
    }

    fun clearImageCache() {
        viewModelScope.launch {
            val freed = try {
                cacheManager.clearImages()
            } catch (_: Exception) {
                0L
            }
            refreshCacheStats()
            _notice.value = "已清理图片缓存 ${formatCacheSize(freed)}"
        }
    }

    fun clearSubtitleCache() {
        viewModelScope.launch {
            val count = try {
                cacheManager.clearSubtitles()
            } catch (_: Exception) {
                0
            }
            refreshCacheStats()
            _notice.value = "已清理字幕缓存约 $count 集"
        }
    }

    fun clearAllCaches() {
        viewModelScope.launch {
            val exclude = playingAudioKey()
            val freed = try {
                cacheManager.clearAll(exclude)
            } catch (_: Exception) {
                CacheStats()
            }
            refreshCacheStats()
            _notice.value = if (exclude != null) "已清理全部缓存 ${formatCacheSize(freed.totalBytes)}（正在播放的音频已跳过）"
            else "已清理全部缓存 ${formatCacheSize(freed.totalBytes)}"
        }
    }
}