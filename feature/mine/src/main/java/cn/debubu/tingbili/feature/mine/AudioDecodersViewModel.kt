package cn.debubu.tingbili.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 「本机音频解码器」独立页：MediaCodecList 实测 audio/ 解码器，仅展示定位用，不做厂商分支。 */
@HiltViewModel
class AudioDecodersViewModel @Inject constructor() : ViewModel() {

    private val _decoders = MutableStateFlow<List<cn.debubu.tingbili.core.media.AudioDecoderInfo>>(emptyList())
    val decoders: StateFlow<List<cn.debubu.tingbili.core.media.AudioDecoderInfo>> = _decoders.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    /** 重读本机音频解码器（IO 线程枚举，失败置空不抛）。 */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _decoders.value = try {
                cn.debubu.tingbili.core.media.AudioDecoderInspector.queryAudioDecoders()
            } catch (_: Exception) {
                emptyList()
            }
            _loading.value = false
        }
    }
}
