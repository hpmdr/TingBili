package cn.debubu.tingbili.core.ui.component

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 全局 Snackbar 宿主：整个 App 只挂一次（MainActivity 壳层）。
 *
 * 串行队列：单个协程 collect + showSnackbar（挂起到自动消失），
 * 后一条必须等前一条消失才显示，天然"按顺序弹出"，不需要额外队列。
 */
@Composable
fun GlobalSnackbarHost(modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        TingBiliMessageBus.messages.collect { hostState.showSnackbar(it) }
    }
    TingBiliSnackbarHost(hostState = hostState, modifier = modifier)
}

/**
 * 全局 Snackbar 相对状态栏的下移量：对齐顶栏（M3 TopAppBar 标准高度）下沿，
 * 落在标题栏下方而不是盖住它。播放页顶栏略矮（56dp），差值可忽略。
 */
val GlobalSnackbarTopOffset = 64.dp
