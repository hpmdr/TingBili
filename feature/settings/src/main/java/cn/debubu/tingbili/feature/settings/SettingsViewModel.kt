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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository
) : ViewModel() {

    val stepSec: StateFlow<Int> = prefs.stepSec.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val dynamicColor: StateFlow<Boolean> = prefs.dynamicColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val timerPresets: StateFlow<Set<Int>> = prefs.timerPresets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), setOf(15, 30, 60, 90))

    fun setStep(v: Int) {
        viewModelScope.launch { prefs.setStep(v.coerceIn(5, 60)) }
    }

    fun setDynamicColor(v: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(v) }
    }

    fun setTimerPresets(presets: Set<Int>) {
        viewModelScope.launch { prefs.setTimerPresets(presets) }
    }
}
