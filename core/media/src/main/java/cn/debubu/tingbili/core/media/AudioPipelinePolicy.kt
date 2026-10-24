package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 音频管线策略：硬解/Offload 开关镜像 + 倍速互斥，不碰 _state（经 Listener 同步）。
 *
 * - offload 开启时 DSP 管线不支持变速：非 1.0x 请求直接拒绝（调用方提示，不断播）；
 * - 开关打开瞬间若正处变速，一次性回 1.0x；
 * - 硬解开关实时镜像到解码器选择器，切换后下一首生效，无需重建播放器。
 */
class AudioPipelinePolicy(
    private val prefs: PreferencesRepository,
    private val transport: PlayerHandle,
) {
    interface Listener {
        fun currentSpeed(): Float
        fun onSpeedResetTo1x()
    }

    sealed interface SpeedResult {
        data class Applied(val speed: Float) : SpeedResult
        data object BlockedByOffload : SpeedResult
    }

    /** offload 开关镜像（Service 侧做实时下发，这里只做倍速互斥；默认开，随 prefs 缺省 true）。 */
    @Volatile
    var offloadEnabled = false
        private set

    private var listener: Listener? = null
    private val jobs = mutableListOf<Job>()

    fun attach(scope: CoroutineScope, listener: Listener) {
        release()
        this.listener = listener
        // 硬解开关实时镜像：解码器每次建实例都查最新值，切换后下一首生效，无需重建播放器
        jobs += scope.launch {
            prefs.audioHwDecode.collect { PreferHardwareAudioDecoderSelector.preferHardware = it }
        }
        // offload 开关实时镜像：开时若倍速非 1.0x 则回 1.0x（DSP 管线不支持变速）
        jobs += scope.launch {
            prefs.audioOffload.collect { applyAudioOffload(it) }
        }
    }

    fun release() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
    /**
     * 倍速请求：钳 0.5x~3.0x；offload 开启时非 1.0x 直接拒绝（BlockedByOffload，不断播）。
     * 调用方按结果更新 UI（Applied→清错，Blocked→提示“DSP 省电开启时不支持倍速”）。
     */
    suspend fun requestSpeed(speed: Float): SpeedResult {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        if (offloadEnabled && clamped != 1f) return SpeedResult.BlockedByOffload
        prefs.setSpeed(clamped)
        try {
            transport.setPlaybackSpeed(clamped)
        } catch (_: Exception) {
        }
        return SpeedResult.Applied(clamped)
    }

    private suspend fun applyAudioOffload(enabled: Boolean) {
        offloadEnabled = enabled
        if (enabled && listener?.currentSpeed() != 1f) {
            prefs.setSpeed(1f)
            try {
                transport.setPlaybackSpeed(1f)
            } catch (_: Exception) {
            }
            listener?.onSpeedResetTo1x()
        }
    }
}
