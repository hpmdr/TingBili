package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「播放与音频设置」：步长 + 字幕字号（播放页 ⋮→播放设置跳这里，唯一入口）。图片格式与缓存在 OtherSettingsViewModel。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesRepository
) : ViewModel() {

    val stepSec: StateFlow<Int> = prefs.stepSec.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    fun setStep(v: Int) {
        viewModelScope.launch { prefs.setStep(v.coerceIn(5, 60)) }
    }

    /** 字幕字号四档（相对系数，跟随系统缩放；0.85 小 / 1.0 标准 / 1.15 大 / 1.3 特大） */
    val subtitleScale: StateFlow<Float> = prefs.subtitleScale
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)
    fun setSubtitleScale(v: Float) {
        viewModelScope.launch { prefs.setSubtitleScale(v) }
    }


    /** DSP 音频卸载开关（默认开；切换后下一首生效，开启时不支持倍速） */
    val audioOffload: StateFlow<Boolean> = prefs.audioOffload.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    /** 音频优先硬解开关（默认开；切换后下一首生效） */
    val audioHwDecode: StateFlow<Boolean> = prefs.audioHwDecode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), true
    )

    fun setAudioHwDecode(v: Boolean) {
        viewModelScope.launch { prefs.setAudioHwDecode(v) }
    }

    fun setAudioOffload(v: Boolean) {
        viewModelScope.launch { prefs.setAudioOffload(v) }
    }
}
