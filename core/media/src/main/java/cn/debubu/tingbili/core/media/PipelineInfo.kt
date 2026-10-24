package cn.debubu.tingbili.core.media

import androidx.media3.common.C
import androidx.media3.common.Format

/**
 * 播放信息快照: 播放页右上播放信息入口读取展示.
 * 解码方式判定与 feature/player 的 pipelineBadge 同规则
 * (c2.android./OMX.google. 为软解, 其余具名解码器视为硬解, 未知返回未知).
 */
data class PipelineInfo(
    val decoderName: String? = null,
    val decoderKind: String = "未知",
    val offloadEnabled: Boolean = false,
    val offloadSleeping: Boolean = false,
    val mimeType: String? = null,
    val codecs: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val speed: Float = 1f,
) {
    companion object {
        fun kindOfDecoder(name: String?): String {
            if (name.isNullOrBlank()) return "未知"
            if (name.startsWith("c2.android.") || name.startsWith("OMX.google.")) return "软解"
            return "硬解"
        }

        fun of(
            decoderName: String?,
            offloadSleeping: Boolean,
            offloadEnabled: Boolean,
            format: Format?,
            speed: Float,
        ): PipelineInfo = PipelineInfo(
            decoderName = decoderName,
            decoderKind = kindOfDecoder(decoderName),
            offloadEnabled = offloadEnabled,
            offloadSleeping = offloadSleeping,
            mimeType = format?.sampleMimeType,
            codecs = format?.codecs,
            bitrateKbps = format?.bitrate
                ?.takeIf { it != C.LENGTH_UNSET && it > 0 }
                ?.div(1000),
            sampleRateHz = format?.sampleRate ?: 0,
            channelCount = format?.channelCount ?: 0,
            speed = speed,
        )
    }
}
