package cn.debubu.tingbili.feature.settings

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * 极验滑块弹窗：HTML/JS 移植自 PiliPlus（geetest_webview_dialog.dart），
 * 只把 flutter 桥换成 Android addJavascriptInterface，其余一字不改。
 * 成功回传 validate/seccode/challenge；关闭/失败走 onCancel。
 */
@Composable
fun GeetestDialog(
    gt: String,
    challenge: String,
    onSuccess: (validate: String, seccode: String, challenge: String) -> Unit,
    onCancel: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val html = remember(gt, challenge) { buildGeetestHtml(gt, challenge) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("安全验证") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                var webView by remember { mutableStateOf<WebView?>(null) }
                DisposableEffect(Unit) {
                    onDispose {
                        webView?.destroy()
                        webView = null
                    }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp, max = 420.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            // 照抄 PiliPlus：伪装成纯手机 Chrome（无 wv 标记），且与取票据时的 UA
                            // 保持完全一致，否则极验风控直接报“安全参数错误”
                            settings.userAgentString =
                                cn.debubu.tingbili.data.bilibili.auth.AuthRepository.BROWSER_UA
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            settings.databaseEnabled = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            android.webkit.CookieManager.getInstance()
                                .setAcceptThirdPartyCookies(this, false)
                            addJavascriptInterface(
                                GeeBridge { raw ->
                                    mainHandler.post { handleGeeMessage(raw, onSuccess, onCancel) { error = it } }
                                },
                                "Android",
                            )
                            loadDataWithBaseURL("https://passport.bilibili.com", html, "text/html", "UTF-8", null)
                        }.also { webView = it }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "请完成滑块验证",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

private class GeeBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun onMessage(msg: String) {
        onMessage(msg)
    }
}

private fun handleGeeMessage(
    raw: String,
    onSuccess: (String, String, String) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val name = raw.substringBefore(":")
    val payload = raw.substringAfter(":", "")
    when (name) {
        "success" -> try {
            val o = JSONObject(payload)
            onSuccess(
                o.optString("geetest_validate"),
                o.optString("geetest_seccode"),
                o.optString("geetest_challenge"),
            )
        } catch (_: Exception) {
            onError("验证结果解析失败，请重试")
        }
        "close" -> onCancel()
        else -> onError("验证码加载失败，请重试")
    }
}

internal fun buildGeetestHtml(gt: String, challenge: String): String {
    val ts = System.currentTimeMillis()
    val js = "var C,S,T,t;" +
        "T=()=>{if(C&&S&&!t){t=Geetest(C).onSuccess(()=>R(\"success\",t.getValidate())).onError(o=>R(\"error\",o)).onClose(o=>R(\"close\",o));t.onReady(()=>t.verify())}};" +
        "geetest_" + ts + "=(d)=>{" +
        "if(!d||d.status!=\"success\"){R(\"error\",JSON.stringify(d));return};" +
        "C=Object.assign({gt:\"$gt\",challenge:\"$challenge\",offline:false,new_captcha:true,product:\"bind\",width:\"100%\",https:true,protocol:\"https://\"},d.data);T()};" +
        "G=()=>{S=1;T()};" +
        "E=()=>{document.getElementById(\"E\").textContent=\"验证码加载失败\";R(\"error\",\"geetest script load failed\")}"
    return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head>" +
        "<style>#E{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;color:red}</style>" +
        "<body><div id=\"E\"></div>" +
        "<script>R=(n,o)=>window.Android.onMessage(n+':'+JSON.stringify(o));$js</script>" +
        "<script src=\"https://static.geetest.com/static/js/fullpage.0.0.0.js\" onload=\"G()\" onerror=\"E()\"></script>" +
        "<script src=\"https://api.geetest.com/gettype.php?gt=$gt&callback=geetest_$ts\" onerror=\"E()\"></script>" +
        "</body></html>"
}
