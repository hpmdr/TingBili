package cn.debubu.tingbili.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 细轨刻度滑轨：4dp 细轨 + 常驻圆点手柄 + 均匀刻度，拖动时手柄放大并逐档触感。
 * 视觉语言对齐播放页进度线与迷你播放器，用于设置类弹层里的秒数/档位选择。
 * 取值恒为 [valueRange].first + k * [step]，不会产生非法中间值。
 */
@Composable
fun TingBiliStepSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: IntRange = 5..60,
    step: Int = 5,
    enabled: Boolean = true,
) {
    val segments = ((valueRange.last - valueRange.first) / step).coerceAtLeast(1)
    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    val haptic = LocalHapticFeedback.current
    val onChange by rememberUpdatedState(onValueChange)
    val snapped = snapStepValue(value, valueRange, step)
    val currentSnapped by rememberUpdatedState(snapped)
    val progress = ((snapped - valueRange.first).toFloat() / (segments * step)).coerceIn(0f, 1f)

    var dragging by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.5f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "stepSliderThumb",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                // 禁用时也保留数值语义：读屏仍能播报当前档位，只是不接受调节
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        val raw = valueRange.first + target.coerceIn(0f, 1f) * segments * step
                        onChange(snapStepValue(raw.roundToInt(), valueRange, step))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, valueRange, step) {
                if (!enabled) return@pointerInput
                // 轨道两端各留出手柄半径，圆点贴边时不会被画布裁掉
                val inset = 7.dp.toPx()
                val trackWidth = (size.width - inset * 2f).coerceAtLeast(1f)

                fun emit(x: Float) {
                    val ratio = ((x - inset) / trackWidth).coerceIn(0f, 1f)
                    val raw = valueRange.first + ratio * segments * step
                    val next = snapStepValue(raw.roundToInt(), valueRange, step)
                    if (next != currentSnapped) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onChange(next)
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    emit(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            emit(change.position.x)
                            break
                        }
                        if (change.positionChanged()) {
                            emit(change.position.x)
                            change.consume()
                        }
                    }
                    dragging = false
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = 7.dp.toPx()
            val trackWidth = (size.width - inset * 2f).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val trackHeight = 4.dp.toPx()
            val trackRadius = CornerRadius(trackHeight / 2f)
            val activeWidth = trackWidth * progress
            val thumbX = inset + activeWidth

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(inset, centerY - trackHeight / 2f),
                size = Size(trackWidth, trackHeight),
                cornerRadius = trackRadius,
            )
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(inset, centerY - trackHeight / 2f),
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = trackRadius,
                )
            }

            val tickWidth = 2.dp.toPx()
            val tickHeight = 6.dp.toPx()
            val tickTop = centerY + trackHeight / 2f + 6.dp.toPx()
            for (index in 0..segments) {
                val x = inset + trackWidth * index / segments
                drawRoundRect(
                    color = tickColor,
                    topLeft = Offset(x - tickWidth / 2f, tickTop),
                    size = Size(tickWidth, tickHeight),
                    cornerRadius = CornerRadius(tickWidth / 2f),
                )
            }

            drawCircle(
                color = activeColor,
                radius = 7.dp.toPx() * thumbScale,
                center = Offset(thumbX, centerY),
            )
        }
    }
}

/** 把任意秒数吸附到 [valueRange] 内按 [step] 对齐的合法档位（越界先钳制再吸附）。 */
internal fun snapStepValue(value: Int, valueRange: IntRange, step: Int): Int {
    val safeStep = step.coerceAtLeast(1)
    val clamped = value.coerceIn(valueRange.first, valueRange.last)
    val index = ((clamped - valueRange.first).toFloat() / safeStep).roundToInt()
    val snapped = valueRange.first + index * safeStep
    return when {
        snapped > valueRange.last ->
            valueRange.first + ((valueRange.last - valueRange.first) / safeStep) * safeStep
        snapped < valueRange.first -> valueRange.first
        else -> snapped
    }
}
