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

/** 「播放与音频设置」：步长。图片格式与缓存在 OtherSettingsViewModel。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository
) : ViewModel() {

    val stepSec: StateFlow<Int> = prefs.stepSec.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    fun setStep(v: Int) {
        viewModelScope.launch { prefs.setStep(v.coerceIn(5, 60)) }
    }

}
