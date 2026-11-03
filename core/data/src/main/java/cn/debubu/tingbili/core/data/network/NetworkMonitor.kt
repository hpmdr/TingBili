package cn.debubu.tingbili.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络连通性观测：只回答"有没有上网能力"，不发请求、不判 B 站可达。
 * 住在 core:data（平台能力、无业务知识）：core:media 与所有 feature 都依赖 core:data，
 * 感知信号只有放这里才够得着；反向（data:bilibili → core:media）会循环。
 *
 * 用途：深夜省电断网导致直链解析失败后，网络恢复时各消费方按"断网标记"门控重试一次；
 * 以及全局离线横幅显隐。注意连接状态只是 hint：不要求系统探测通过（VALIDATED）——
 * captive portal 探测被路由器/VPN/私有 DNS 拦住时网络其实能用，误判离线会让横幅常驻
 * （2026-11 用户反馈），真正的断网以请求结果为准（[isConnectivityError] 分类）。
 */
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>

    /** 主动重查一次（回前台时校准，防回调被省电丢弃后状态 stale）。 */
    fun refresh() {}
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
        // 事件驱动，不回读 activeNetwork：断网瞬间回读仍可能拿到"将死网络"的 caps
        //（带 INTERNET），状态会卡在 true 且再无回调来纠正（2026-11 真机实测踩过：
        // 彻底断网后横幅永不出现）。切换到别的网络时 onAvailable 会立刻翻回 true。
        override fun onAvailable(network: Network) { _isOnline.value = true }

        override fun onLost(network: Network) { _isOnline.value = false }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure {
                // 监听失效 = 状态永远收不到更新，与其让横幅按初始值常驻误报，不如恒在线
                //（自动重试靠 false→true 跳变，恒在线只是不触发，不会帮倒忙）；日志留痕可查。
                Log.w("TingBiliNetwork", "NetworkCallback registration failed, degrade to always-online", it)
                _isOnline.value = true
            }
    }

    /** 应用级单例，跟进程同寿命；PlayerManager 不代为释放（见其 release 注释）。 */
    override fun release() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    override fun refresh() {
        _isOnline.value = currentlyOnline()
    }

    private fun currentlyOnline(): Boolean = runCatching {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}
