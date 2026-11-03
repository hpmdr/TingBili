package cn.debubu.tingbili.core.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

/**
 * 只测"在线判定语义"（currentlyOnline 的门槛）：有没有 INTERNET capability，
 * 不再要求系统探测通过（VALIDATED）——captive portal 探测被拦但真实可用的网络不许误判离线。
 * 回调投递依赖真实系统时序，不在单测范围。
 */
@RunWith(RobolectricTestRunner::class)
class AndroidNetworkMonitorTest {

    private fun setCaps(caps: NetworkCapabilities?) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    /**
     * NetworkCapabilities.addCapability 是 @SystemApi，编译 stub 没有；
     * Robolectric 运行时（android-all）有真实现，走反射构造测试用 caps。
     */
    private fun caps(vararg capsToAdd: Int): NetworkCapabilities =
        NetworkCapabilities().also { c ->
            val add = NetworkCapabilities::class.java
                .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
            capsToAdd.forEach { add.invoke(c, it) }
        }

    @Test
    fun `internet without validated is online`() {
        setCaps(caps(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(newMonitor().isOnline.value)
    }

    @Test
    fun `internet with validated is online`() {
        setCaps(
            caps(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            ),
        )
        assertTrue(newMonitor().isOnline.value)
    }

    @Test
    fun `no internet capability is offline`() {
        setCaps(NetworkCapabilities())
        assertFalse(newMonitor().isOnline.value)
    }

    @Test
    fun `refresh recomputes state`() {
        setCaps(caps(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        val m = newMonitor()
        assertTrue(m.isOnline.value)
        setCaps(null)
        m.refresh()
        assertFalse(m.isOnline.value)
    }

    /** 回调事件驱动、不回读 activeNetwork 快照：onLost 直接翻 false，别被将死网络的旧快照卡在 true。 */
    @Test
    fun `callback events flip state without snapshot race`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCallbacksEnabled(true)
        setCaps(caps(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        val m = newMonitor()
        assertTrue(m.isOnline.value)

        val registered = shadowOf(cm).networkCallbacks
        assertTrue(registered.isNotEmpty())
        val cb = registered.last()
        cb.onLost(ShadowNetwork.newInstance(1))
        assertFalse(m.isOnline.value)
        cb.onAvailable(ShadowNetwork.newInstance(2))
        assertTrue(m.isOnline.value)
        cb.onCapabilitiesChanged(
            ShadowNetwork.newInstance(2),
            caps(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
        assertTrue(m.isOnline.value)
        cb.onCapabilitiesChanged(ShadowNetwork.newInstance(2), NetworkCapabilities())
        assertFalse(m.isOnline.value)
    }

    private fun newMonitor(): AndroidNetworkMonitor {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return AndroidNetworkMonitor(ctx)
    }
}
