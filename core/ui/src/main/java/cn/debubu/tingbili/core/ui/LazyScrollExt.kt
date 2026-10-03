package cn.debubu.tingbili.core.ui

import androidx.compose.foundation.lazy.LazyListState

/**
 * 把 [index] 处的列表项滚到**屏幕中部**而不是顶部。
 *
 * `animateScrollToItem` 默认把目标项顶到视口上沿，列表一进来就跳到第 N 项时
 * 看着像"从这条开始"，而且上半屏全空。往负方向偏移半个视口高度可以落在中部，
 * 既保留了上方内容的上下文，目标项本身也一眼能看到。
 *
 * scrollOffset 为负数表示把该项往下推（正数会把它推到视口上方之外）。
 */
suspend fun LazyListState.scrollToItemCentered(index: Int) {
    if (index < 0) return
    val viewportHeight = layoutInfo.viewportSize.height
    if (viewportHeight <= 0) return
    animateScrollToItem(index, -(viewportHeight / 2))
}

/**
 * [LazyListState] 的下标是整列的下标，而业务下标（分P/第几集）只对应列表中一段连续 item，
 * 中间隔着封面、信息块、操作条等固定表头。直接拿业务下标去滚动会滚错位置。
 *
 * 表头数量会随内容变化（空态、单集、多P 等分支会增减 item），所以用总数反推而不是写死常量：
 * [totalItems] - [itemCount] - [trailingItems] 即表头数量。
 */
fun LazyListState.headerOffsetOf(itemCount: Int, trailingItems: Int = 0): Int {
    val header = layoutInfo.totalItemsCount - itemCount - trailingItems
    return header.coerceAtLeast(0)
}