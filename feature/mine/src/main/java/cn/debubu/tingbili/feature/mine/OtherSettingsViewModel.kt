package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.SimpleCache
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「其他设置」：图片格式与音频缓存 */
@HiltViewModel
class OtherSettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val audioCache: SimpleCache
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

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeBytes.value = try {
                audioCache.cacheSpace
            } catch (_: Exception) { 0L }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                audioCache.keys.forEach { audioCache.removeResource(it) }
            } catch (_: Exception) { }
            refreshCacheSize()
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
