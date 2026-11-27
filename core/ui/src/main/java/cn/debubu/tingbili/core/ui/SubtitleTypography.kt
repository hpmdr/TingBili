package cn.debubu.tingbili.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle

/**
 * 全屏字幕排印（方案 A）：高亮 16sp×1.25，正文 14sp×1.1，行高同倍放大保固定行高比。
 *
 * 播放页与「字幕字号」设置页的实时预览共用这一份公式——预览卡所见即所得，
 * 改倍数只改这里，不会出现预览与实际字幕漂移。
 */
object SubtitleTypography {
    /** 高亮行倍数（titleMedium 16sp × 1.25 = 20sp） */
    const val HIGHLIGHT_SCALE = 1.25f

    /** 正文行倍数（bodyMedium 14sp × 1.1 ≈ 15.4sp） */
    const val BODY_SCALE = 1.1f

    /** 全屏高亮行样式：titleMedium × 高亮倍数 × 用户字号系数 */
    @Composable
    fun highlight(userScale: Float): TextStyle =
        scaled(MaterialTheme.typography.titleMedium, HIGHLIGHT_SCALE * userScale)

    /** 全屏正文行样式：bodyMedium × 正文倍数 × 用户字号系数 */
    @Composable
    fun body(userScale: Float): TextStyle =
        scaled(MaterialTheme.typography.bodyMedium, BODY_SCALE * userScale)

    /** 把倍数乘进文字样式（fontSize 与 lineHeight 同步放大，保固定行高比） */
    private fun scaled(base: TextStyle, scale: Float): TextStyle = base.copy(
        fontSize = base.fontSize * scale,
        lineHeight = base.lineHeight * scale,
    )
}
