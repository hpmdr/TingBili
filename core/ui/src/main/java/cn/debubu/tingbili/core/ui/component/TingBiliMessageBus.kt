package cn.debubu.tingbili.core.ui.component

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局一次性提示总线。
 *
 * 整个 App 只有一个 Snackbar 宿主（[GlobalSnackbarHost]，挂在 MainActivity 壳层顶部），
 * 任何页面发提示都走这里，位置与样式全局统一。
 *
 * 为什么不是每页一个 SnackbarHostState：那样每页各定位置，底部操作栏会被盖住；
 * 且提示的显示时机/顺序逐页各自为政。发消息方只依赖本对象，不关心谁渲染。
 */
object TingBiliMessageBus {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** 只给全局宿主订阅；replay=0 避免新订阅者收到历史提示。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 发一条提示。多条按发送顺序排队，前一条消失后才显示下一条。 */
    fun show(message: String) {
        _messages.tryEmit(message)
    }
}
