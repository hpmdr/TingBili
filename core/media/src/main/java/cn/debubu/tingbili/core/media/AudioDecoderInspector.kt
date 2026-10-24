package cn.debubu.tingbili.core.media

import android.media.MediaCodecList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本机音频解码器实测: 经 MediaCodecList 枚举 audio/ 解码器.
 * 只做展示与问题定位(设置页其他设置), 不做厂商分支;
 * 选型一律以 MediaCodecSelector 的 hardwareAccelerated/softwareOnly/vendor 标志为准.
 */
data class AudioDecoderInfo(
    val name: String,
    val mimeType: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
) {
    val kindLabel: String get() = when {
        hardwareAccelerated && !softwareOnly -> "硬解"
        softwareOnly -> "软解"
        else -> "未知"
    }
    val vendorLabel: String get() = if (vendor) "厂商" else "平台"
}

object AudioDecoderInspector {

    /** 按硬解优先排序: HW 在前, 未知居中, 纯软在后; 同组按名称/mime 排序. */
    suspend fun queryAudioDecoders(): List<AudioDecoderInfo> = withContext(Dispatchers.IO) {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { it.startsWith("audio/") }
                        .map { mime ->
                            AudioDecoderInfo(
                                name = info.name,
                                mimeType = mime,
                                hardwareAccelerated = info.isHardwareAccelerated,
                                softwareOnly = info.isSoftwareOnly,
                                vendor = info.isVendor,
                            )
                        }
                }
                .distinctBy { it.name + "|" + it.mimeType }
                .sortedWith(
                    compareBy(
                        { !(it.hardwareAccelerated && !it.softwareOnly) },
                        { it.softwareOnly },
                        { it.name },
                        { it.mimeType },
                    )
                )
        } catch (_: Exception) {
            emptyList()
        }
    }
}
