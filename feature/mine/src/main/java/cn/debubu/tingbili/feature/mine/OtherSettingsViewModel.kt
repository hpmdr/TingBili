package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.cache.CacheManager
import cn.debubu.tingbili.core.data.cache.CacheStats
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「其他设置」：图片格式、首页推荐词与缓存管理（音频 / 图片 / 字幕分别统计清理） */
@HiltViewModel
class OtherSettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val cacheManager: CacheManager,
    private val player: PlayerManager,
) : ViewModel() {

    val imageFormat: StateFlow<ImageFormat> = prefs.imageFormat.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ImageFormat.AVIF
    )
    val cacheMaxMb: StateFlow<Int> = prefs.cacheMaxMb.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PreferencesRepository.DEFAULT_CACHE_MAX_MB
    )

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

    fun setImageFormat(v: ImageFormat) {
        viewModelScope.launch { prefs.setImageFormat(v) }
    }

    fun setCacheMaxMb(v: Int) {
        viewModelScope.launch {
            prefs.setCacheMaxMb(v.coerceIn(PreferencesRepository.MIN_CACHE_MAX_MB, PreferencesRepository.MAX_CACHE_MAX_MB))
        }
    }

}

private fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0L -> "0MB"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}
