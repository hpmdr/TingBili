package cn.debubu.tingbili.core.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 全局 Snackbar 宿主。
 *
 * 不用 M3 默认样式的原因：默认 Snackbar 取 `inverseSurface`/`inverseOnSurface`，
 * 深色主题下会翻成"浅色药丸 + 深色字"，跟本 App 的深灰/纯黑主题完全不搭（2026-11 反馈"丑"）。
 * 这里改用主题内的层级色：容器 surfaceContainerHigh、文字 onSurface、动作 primary，
 * 圆角与描边贴近卡片；纯黑风格下会跟着 applyAmoledBlack 变成 #1A1A1A，不会发光刺眼。
 */
@Composable
fun TingBiliSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = shape,
            containerColor = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            actionColor = colors.primary,
            actionContentColor = colors.primary,
            dismissActionContentColor = colors.onSurfaceVariant,
            modifier = Modifier.border(
                width = 1.dp,
                color = colors.onSurface.copy(alpha = 0.08f),
                shape = shape,
            ),
        )
    }
}
