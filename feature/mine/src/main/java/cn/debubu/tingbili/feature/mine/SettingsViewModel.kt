package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「播放设置」：步长 + 字幕字号 + 睡眠偏好 + 解码开关 + 默认音质（播放页 ⋮→播放设置跳这里，唯一入口）。图片格式与推荐词在通用设置，缓存在存储设置。 */
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

    /** 睡眠定时「到点后播完本章再停」默认开关（落盘；播放页定时弹窗沿用，下次定时生效） */
    val sleepFinishChapter: StateFlow<Boolean> = prefs.sleepFinishChapter.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), false
    )
    fun setSleepFinishChapter(v: Boolean) {
        viewModelScope.launch { prefs.setSleepFinishChapter(v) }
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

    /** 默认音质偏好（默认标准 132K；未知值回落默认；切换后下一首生效） */
    val audioQuality: StateFlow<Int> = prefs.audioQuality.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PreferencesRepository.DEFAULT_AUDIO_QUALITY
    )

    fun setAudioQuality(code: Int) {
        val safe = if (AudioQuality.isKnown(code)) code else AudioQuality.DEFAULT_CODE
        viewModelScope.launch { prefs.setAudioQuality(safe) }
    }
}

/** 字幕缩放系数 → 四档中文名（就近匹配，兜底标准；播放设置行内值与我的页副标题共用） */
fun subtitleScaleLabel(scale: Float): String = when {
    scale < 0.925f -> "小"
    scale < 1.075f -> "标准"
    scale < 1.225f -> "大"
    else -> "特大"
}