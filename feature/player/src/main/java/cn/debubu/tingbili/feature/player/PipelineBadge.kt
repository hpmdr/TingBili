package cn.debubu.tingbili.feature.player

/**
 * 播放页管线徽标文案：解码器名 + offload 生效态。
 * - offload 生效优先展示（此时解码器名无意义，DSP 接管）；
 * - 解码器名以 c2.android./OMX.google. 开头为软解，其余视为硬解；
 * - 解码器未知（尚未初始化）返回 null，界面不占位。
 */
internal fun pipelineBadge(decoderName: String?, offloadSleeping: Boolean): String? {
    if (decoderName == null) return null
    if (offloadSleeping) return "DSP offload 生效中"
    val hw = !(decoderName.startsWith("c2.android.") || decoderName.startsWith("OMX.google."))
    return (if (hw) "硬解" else "软解") + " · " + decoderName
}
