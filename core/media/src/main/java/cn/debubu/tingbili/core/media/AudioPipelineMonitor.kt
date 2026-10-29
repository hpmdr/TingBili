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

    private val offloadedPlaybackState = MutableStateFlow(false)
    /** 本次会话音频是否真的走 DSP offload（冻结后位置校准只在 offload 下需要） */
    val offloadedPlayback: StateFlow<Boolean> = offloadedPlaybackState.asStateFlow()

    private val offloadEverActiveState = MutableStateFlow(false)
    /**
     * 当前这一轨是否被 DSP 接管（粘性）。
     * 不少机型只回调 sleepingForOffload、不回调 onOffloadedPlayback，
     * 且 sleeping 只在缓冲满时短暂为真；用瞬时值判定会把"正在 DSP 解码"误报成"已回落 CPU"。
     */
    val offloadEverActive: StateFlow<Boolean> = offloadEverActiveState.asStateFlow()

    fun onDecoderInitialized(name: String) {
        decoderNameState.value = name
        // 出现 CPU 解码器 = 这一轨没走 offload，撤销粘性标记
        offloadEverActiveState.value = false
    }

    fun onOffloadSleepingChanged(sleeping: Boolean) {
        offloadSleepingState.value = sleeping
        if (sleeping) offloadEverActiveState.value = true
    }

    fun onOffloadedPlaybackChanged(offloaded: Boolean) {
        offloadedPlaybackState.value = offloaded
        if (offloaded) offloadEverActiveState.value = true
    }

    private val audioUnderrunsState = MutableStateFlow(0L)
    /**
     * 本次会话累计 underrun 次数（API 29+ 才回调）。
     * offload 下缓冲被 HAL 抽空才会记 it；冻结/断流后通常增长，是「真的断过」的硬证据。
     */
    val audioUnderruns: StateFlow<Long> = audioUnderrunsState.asStateFlow()

    fun onAudioUnderrun() {
        audioUnderrunsState.value += 1
    }

    private val audioFormatState = MutableStateFlow<Format?>(null)
    /** 当前音频轨格式(采样率/声道/码率/mime), 播放信息页读取. null=尚未回调. */
    val audioFormat: StateFlow<Format?> = audioFormatState.asStateFlow()

    fun onAudioFormatChanged(format: Format) {
        audioFormatState.value = format
        // 注意：这里不能清 decoderName / offload 标记。
        // 解码器通常复用，切集时只会回调 formatChanged、不会重新回调 onAudioDecoderInitialized，
        // 一清就永远补不回来（表现为播放信息页一直「读取中」）。
        // offload 状态由 onOffloadSleepingChanged / onOffloadedPlayback / onAudioDecoderInitialized 自行维护。
    }

    /** 清空"本轨"相关的判定（切轨、停止播放、单测复位） */
    fun resetTrackState() {
        decoderNameState.value = null
        offloadEverActiveState.value = false
    }
}
