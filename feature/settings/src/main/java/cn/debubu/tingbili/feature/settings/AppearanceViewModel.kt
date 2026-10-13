package cn.debubu.tingbili.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val prefs: PreferencesRepository
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = prefs.themeMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.DEFAULT
    )
    val customThemeColor: StateFlow<Int> = prefs.customThemeColor.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PreferencesRepository.DEFAULT_THEME_COLOR
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setCustomThemeColor(color: Int) {
        viewModelScope.launch { prefs.setCustomThemeColor(color) }
    }
}
