package cn.debubu.tingbili.core.media

import android.content.ComponentName
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * App 级媒体会话连接。负责拉起前台播放服务、异步建立 MediaController、
 * 把 Player.Listener 扇出到新旧 Controller。Controller 就绪前发出的命令由调用方排队。
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 指向 TingBiliPlaybackService 的会话令牌，对外暴露以便单测不断言实现细节。 */
    val sessionToken: SessionToken =
        SessionToken(context, ComponentName(context, TingBiliPlaybackService::class.java))

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pendingListeners = mutableListOf<Player.Listener>()

    /** Controller 就绪前暂存监听器，就绪后一次性挂上；换 Controller 时重新挂。 */
    fun addListener(listener: Player.Listener) {
        pendingListeners += listener
        _controller.value?.addListener(listener)
    }

    /**
     * 建立会话连接，重复调用无副作用。
     * 前台服务不在这里手动 startForegroundService：Media3 在收到播放请求后自行起服务并 startForeground，
     * 手动提前起服务会让「起了服务但没在 5s 内 startForeground」变成系统杀进程的理由
     * （REVIEW_REPORT G-12）。断连（Service 被系统回收/任务移除）后清空引用，下次 awaitController 自动重连。
     */
    fun connect() {
        if (controllerFuture != null) return
        val future = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (_controller.value === controller) {
                        _controller.value = null
                        controllerFuture = null
                    }
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                // 连接失败（服务起不来/被杀）时不能让主线程抛 ExecutionException
                runCatching { future.get() }.onSuccess { controller ->
                    pendingListeners.forEach { controller.addListener(it) }
                    _controller.value = controller
                }.onFailure {
                    controllerFuture = null
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** 挂起直到 Controller 就绪（内部自动 connect）。 */
    suspend fun awaitController(): MediaController {
        connect()
        return controller.filterNotNull().first()
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        pendingListeners.clear()
        _controller.value = null
    }

    @VisibleForTesting
    internal fun pendingListenerCountForTest(): Int = pendingListeners.size
}
