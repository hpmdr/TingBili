package cn.debubu.tingbili.core.data.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 断网类失败的稳定标记，同时是用户可读文案（弹窗/横幅可直接用，不再二次翻译）。
 * 住 core:data 的原因见 NetworkMonitor：core:media 的重试决策方必须够得着，
 * 放 data:bilibili 会因 data:bilibili → core:media 的依赖方向形成循环。
 */
const val CONNECTIVITY_MARK = "网络连接失败"

/**
 * cause 链里有没有断网：认 JDK 网络异常 + OkHttp 的 Unable to resolve host + 稳定标记。
 * 只认"网的问题"：412/401/404 等业务错一律返回 false，永不触发网络恢复重试。
 */
fun isConnectivityError(t: Throwable?): Boolean {
    var c = t
    repeat(10) {
        c ?: return false
        if (c is UnknownHostException || c is ConnectException || c is SocketTimeoutException) return true
        val msg = c.message.orEmpty()
        if (CONNECTIVITY_MARK in msg || "Unable to resolve host" in msg) return true
        c = c.cause
    }
    return false
}
