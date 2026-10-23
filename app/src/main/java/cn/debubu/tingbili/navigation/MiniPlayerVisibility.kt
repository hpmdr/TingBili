package cn.debubu.tingbili.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * mini 播放条显隐状态（方案 A'）：
 * - 上滑浏览列表时收起、下滑回看时出现（只收 mini 条，底栏不动）；
 * - 条上上滑手势关闭后，同首歌不再出现，切歌（trackKey 变化）自动重置。
 */
class MiniPlayerVisibility {
    var scrollVisible by mutableStateOf(true)
        private set

    private var dismissedKey: String? by mutableStateOf(null)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy < -SCROLL_DEAD_ZONE_PX) {
                scrollVisible = false
            } else if (dy > SCROLL_DEAD_ZONE_PX) {
                scrollVisible = true
            }
            return Offset.Zero
        }
    }

    fun onTrackChanged(trackKey: String?) {
        if (trackKey != dismissedKey) {
            dismissedKey = null
            scrollVisible = true
        }
    }

    fun dismiss(trackKey: String) {
        dismissedKey = trackKey
    }

    fun isVisible(trackKey: String?): Boolean =
        trackKey != null && dismissedKey != trackKey && scrollVisible

    /** 是否被手动关闭（布局占位走 AnimatedVisibility 收起，与滚动隐藏分开） */
    fun isDismissed(trackKey: String?): Boolean = trackKey != null && dismissedKey == trackKey

    companion object {
        /** 滚动死区，避免手指微抖导致条子闪烁 */
        const val SCROLL_DEAD_ZONE_PX = 6f
    }
}

@Composable
fun rememberMiniPlayerVisibility(): MiniPlayerVisibility = remember { MiniPlayerVisibility() }
