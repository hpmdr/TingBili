package cn.debubu.tingbili.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首页回顶事件总线（应用单例，见 AppDataModule）。
 *
 * 背景：回顶手势在底栏/Rail（导航层），列表 state 在首页 VM。
 * 跨 backStackEntry 共享 VM 会踩条件 hooks + 首次组合时序坑，故用单例事件解耦：
 * 导航层只管发，首页只管收。计数器语义（只增不减，冷启动 0 即无事发生）。
 */
class HomeScrollTopBus {
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    fun requestScrollTop() {
        _tick.value += 1
    }
}
