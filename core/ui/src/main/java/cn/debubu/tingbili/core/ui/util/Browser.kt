package cn.debubu.tingbili.core.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 浏览器打开 URL（更新下载走系统浏览器，不在站内下：
 * 免 REQUEST_INSTALL_PACKAGES / FileProvider / 安装器适配）。
 * 找不到浏览器返回 false，调用方给 Snackbar 提示。
 */
fun openUrlInBrowser(context: Context, url: String): Boolean =
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    } catch (_: Exception) {
        false
    }
