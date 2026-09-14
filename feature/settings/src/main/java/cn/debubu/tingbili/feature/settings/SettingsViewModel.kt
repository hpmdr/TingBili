package cn.debubu.tingbili.feature.settings

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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository,
    private val audioCache: SimpleCache
) : ViewModel() {

    val stepSec: StateFlow<Int> = prefs.stepSec.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val dynamicColor: StateFlow<Boolean> = prefs.dynamicColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val imageFormat: StateFlow<ImageFormat> = prefs.imageFormat.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ImageFormat.AVIF)
    val timerPresets: StateFlow<Set<Int>> = prefs.timerPresets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), setOf(15, 30, 60, 90))

    private val _cacheSizeMb = MutableStateFlow(0L)
    val cacheSizeMb: StateFlow<Long> = _cacheSizeMb.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeMb.value = try {
                audioCache.cacheSpace / (1024 * 1024)
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

    fun setStep(v: Int) {
        viewModelScope.launch { prefs.setStep(v.coerceIn(5, 60)) }
    }

    fun setDynamicColor(v: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(v) }
    }

    fun setImageFormat(v: ImageFormat) {
        viewModelScope.launch { prefs.setImageFormat(v) }
    }

    fun setTimerPresets(presets: Set<Int>) {
        viewModelScope.launch { prefs.setTimerPresets(presets) }
    }
}
