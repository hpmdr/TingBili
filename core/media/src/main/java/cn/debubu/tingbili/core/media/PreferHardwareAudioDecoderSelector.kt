package cn.debubu.tingbili.core.media

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 音频优先硬解的解码器选择器：只做重排，不改变兜底。
 *
 * - 仅对音频 mime 生效，视频选型原样不动；
 * - 有硬解时硬解前移、软解保留在后：硬解初始化失败 ExoPlayer 会按序尝试下一个，
 *   即“硬解用不了自动软解”；
 * - 本机无硬解（列表里没有 hardwareAccelerated）时原样返回，行为与系统默认一致。
 *
 * 全局单例（无需 DI）：开关状态由 [PlayerManager] 从设置实时镜像到 [preferHardware]；
 * 选择器在每次初始化解码器（切歌/下一首）时被查询，因此切换后下一首生效，
 * 无需重建播放器。
 */
object PreferHardwareAudioDecoderSelector : MediaCodecSelector {

    /** 设置页开关的实时镜像，默认开。 */
    @Volatile
    var preferHardware: Boolean = true

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType, requiresSecureDecoder, requiresTunnelingDecoder
        )
        return rankForMime(mimeType, infos)
    }

    internal fun rankForMime(mimeType: String, infos: List<MediaCodecInfo>): List<MediaCodecInfo> {
        if (!preferHardware || !mimeType.startsWith("audio/")) return infos
        if (infos.none { it.hardwareAccelerated }) return infos
        // 稳定排序：硬解整体前移，同组内保持系统原有顺序。
        return infos.sortedByDescending { it.hardwareAccelerated }
    }
}
