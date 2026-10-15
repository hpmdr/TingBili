package cn.debubu.tingbili.feature.settings

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebViewClient
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
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                    Log.d(TAG, "console [${msg.messageLevel()}] ${msg.message()} @${msg.lineNumber()}")
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest,
                                    error: android.webkit.WebResourceError,
                                ) {
                                    Log.d(TAG, "page error url=${request.url} code=${error.errorCode}")
                                }
                            }
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

// 注意：构造参数绝不能与方法同名——Kotlin 里 onMessage(msg) 会优先解析成
// 方法自身的递归调用（而不是参数 lambda），之前因此 StackOverflow，桥直接死亡。
private class GeeBridge(private val onJsMessage: (String) -> Unit) {
    @JavascriptInterface
    fun onMessage(msg: String) {
        onJsMessage(msg)
    }
}

private const val TAG = "GeeDialog"

private fun handleGeeMessage(
    raw: String,
    onSuccess: (String, String, String) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    // 只打长度与类型，票据内容不落日志
    Log.d(TAG, "bridge msg name=${raw.substringBefore(":")} len=${raw.length}")
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

/**
 * 极验初始化改用 gt.js + initGeetest（对标 biu，同 web 接口已验证可行）。
 * 之前照抄 PiliPlus 的 fullpage.js 方案，在 B 站当前下发的 challenge 上报“配置错误”；
 * fullpage 与 gt.js 是两代 SDK，challenge 只能配对能解析它的那一代。
 */
internal fun buildGeetestHtml(gt: String, challenge: String): String {
    // 注意：三引号内 $gt/$challenge 是 Kotlin 插值，其余 $ 一律转义成 ${'$'}
    return """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width"></head>
<style>#E{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;color:red}</style>
<body><div id="E"></div>
<script>
R=(n,o)=>window.Android.onMessage(n+':'+JSON.stringify(o));
W=()=>{
if(typeof initGeetest!=="function"){E();return;}
initGeetest({gt:"$gt",challenge:"$challenge",offline:false,new_captcha:true,product:"bind",https:true},
function(c){
c.onReady(function(){c.verify();});
c.onSuccess(function(){var r=c.getValidate();
if(r&&typeof r!=="boolean"){R("success",r)}else{R("error","empty validate")}});
c.onError(function(e){R("error",JSON.stringify(e||"geetest error"));});
if(c.onClose){c.onClose(function(){R("close","")})}});};
E=()=>{document.getElementById("E").textContent="VERIFY_LOAD_FAIL";R("error","gt.js load failed")}
</script>
<script src="https://static.geetest.com/static/tools/gt.js" onload="W()" onerror="E()"></script>
</body></html>
""".trimIndent()
}
