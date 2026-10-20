package cn.debubu.tingbili.navigation

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController

/** 推荐 tab 双击窗口：已在首页时 400ms 内连点第二下即回顶（否则走原导航） */
internal const val HOME_TAB_DOUBLE_TAP_WINDOW_MS = 400L

/**
 * 推荐 tab 点击处理（底栏 + Rail 共用）：已在首页的双击→回顶事件，
 * 其余→原导航（launchSingleTop + 状态恢复）。计时各调用处自记，互不干扰。
 */
@Composable
internal fun rememberHomeTabClick(
    navController: NavController,
    isHomeSelected: Boolean,
    mainViewModel: MainViewModel,
): () -> Unit {
    var lastTapMs by remember { mutableLongStateOf(0L) }
    return {
        val now = SystemClock.uptimeMillis()
        if (isHomeSelected && now - lastTapMs < HOME_TAB_DOUBLE_TAP_WINDOW_MS) {
            mainViewModel.requestHomeScrollTop()
        } else {
            navController.navigate(HomeRoute) {
                launchSingleTop = true
                restoreState = true
                popUpTo(navController.graph.startDestinationId) { saveState = true }
            }
        }
        lastTapMs = now
    }
}
