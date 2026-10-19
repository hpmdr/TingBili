package cn.debubu.tingbili.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「播放与音频设置」：步长 / 定时预设。图片格式与缓存在 OtherSettingsViewModel。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository
) : ViewModel() {

    val stepSec: StateFlow<Int> = prefs.stepSec.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val timerPresets: StateFlow<Set<Int>> = prefs.timerPresets.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), setOf(30, 60, 90, 120)
    )

    fun setStep(v: Int) {
        viewModelScope.launch { prefs.setStep(v.coerceIn(5, 60)) }
    }

    fun setTimerPresets(presets: Set<Int>) {
        viewModelScope.launch { prefs.setTimerPresets(presets) }
    }
}
