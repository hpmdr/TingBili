package cn.debubu.tingbili.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.media3.common.Format

/**
 * 音频管线实时状态（同进程单例）：Service 侧 ExoPlayer 回调写入，播放页读取展示。
 * 进程重建即清零，展示"本次会话"状态，不做持久化。
 */
object AudioPipelineMonitor {
    private val decoderNameState = MutableStateFlow<String?>(null)
    val decoderName: StateFlow<String?> = decoderNameState.asStateFlow()

    private val offloadSleepingState = MutableStateFlow(false)
    val offloadSleeping: StateFlow<Boolean> = offloadSleepingState.asStateFlow()

    fun onDecoderInitialized(name: String) {
        decoderNameState.value = name
    }

    fun onOffloadSleepingChanged(sleeping: Boolean) {
        offloadSleepingState.value = sleeping
    }

    private val audioFormatState = MutableStateFlow<Format?>(null)
    /** 当前音频轨格式(采样率/声道/码率/mime), 播放信息页读取. null=尚未回调. */
    val audioFormat: StateFlow<Format?> = audioFormatState.asStateFlow()

    fun onAudioFormatChanged(format: Format) {
        audioFormatState.value = format
    }
}
