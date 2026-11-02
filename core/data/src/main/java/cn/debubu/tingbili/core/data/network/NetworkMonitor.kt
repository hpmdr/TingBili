package cn.debubu.tingbili.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络连通性观测：只回答"有没有经过验证的上网能力"，不发请求、不判 B 站可达。
 * 住在 core:data（平台能力、无业务知识）：core:media 与所有 feature 都依赖 core:data，
 * 感知信号只有放这里才够得着；反向（data:bilibili → core:media）会循环。
 *
 * 用途：深夜省电断网导致直链解析失败后，网络恢复时各消费方按"断网标记"门控重试一次。
 * 注意连接状态只是 hint：VALIDATED 能过滤 captive portal，但真正以请求结果为准，
 * 恢复触发的重拉仍要认 [isConnectivityError] 分类，不对 412/401 等业务错重试。
 */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
    fun release() {}
}

/** 无网络监听时的兜底：永远在线，不触发自动重试（不需要该行为的单测默认用它）。 */
object AlwaysOnlineNetworkMonitor : NetworkMonitor {
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
}

@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkMonitor {
    private val connectivity =
        context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentlyOnline())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    private fun refresh() {
        _isOnline.value = currentlyOnline()
    }

    private fun currentlyOnline(): Boolean = runCatching {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /** 应用级单例，跟进程同寿命；PlayerManager 不代为释放（见其 release 注释）。 */
    override fun release() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}
