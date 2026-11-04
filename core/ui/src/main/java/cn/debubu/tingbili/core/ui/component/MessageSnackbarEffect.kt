package cn.debubu.tingbili.core.ui.component

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * 一次性 message → Snackbar 的统一消费入口。
 *
 * 为什么不能直接写 `LaunchedEffect(message) { consume(); showSnackbar(it) }`：
 * `consume()` 会把 message 置空，而 effect 以 message 为 key，状态一变就重启并**取消**
 * 上一个协程；此时 `showSnackbar` 正挂起等待用户/超时，被取消后会立刻清掉当前 Snackbar，
 * 提示只存在 0 帧。表现就是“点了没有任何提示”（2026-11 倍速反馈的根因）。
 *
 * 这里 consume 之后把 show 丢到独立 scope，effect 重启取消不到它。
 */
@Composable
fun MessageSnackbarEffect(
    message: String?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        message?.let {
            onConsumed()
            scope.launch { hostState.showSnackbar(it) }
        }
    }
}
