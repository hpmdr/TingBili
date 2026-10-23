package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.DarkMode
import cn.debubu.tingbili.core.data.model.DarkThemeStyle
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
    val darkMode: StateFlow<DarkMode> = prefs.darkMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), DarkMode.FOLLOW_SYSTEM
    )
    val darkThemeStyle: StateFlow<DarkThemeStyle> = prefs.darkThemeStyle.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), DarkThemeStyle.DARK_GRAY
    )
    val customThemeColor: StateFlow<Int> = prefs.customThemeColor.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PreferencesRepository.DEFAULT_THEME_COLOR
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { prefs.setDarkMode(mode) }
    }

    fun setDarkThemeStyle(style: DarkThemeStyle) {
        viewModelScope.launch { prefs.setDarkThemeStyle(style) }
    }

    fun setCustomThemeColor(color: Int) {
        viewModelScope.launch { prefs.setCustomThemeColor(color) }
    }
}
