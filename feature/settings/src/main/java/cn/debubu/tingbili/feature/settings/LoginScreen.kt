package cn.debubu.tingbili.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 登录页：Web 扫码（主）+ Cookie 粘贴（兜底）。
 * 成功后由调用方返回（session 非空即走 onSuccess）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    vm: LoginViewModel = hiltViewModel(),
    autoStartQr: Boolean = true,
) {
    val session by vm.session.collectAsStateWithLifecycle()
    LaunchedEffect(session) {
        if (session != null) onSuccess()
    }
    DisposableEffect(autoStartQr) {
        if (autoStartQr) vm.startQr()
        onDispose { vm.stopQr() }
    }

    var tab by remember { mutableIntStateOf(0) }
    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "登录哔哩哔哩",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("扫码登录") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Cookie 导入") })
            }
            if (tab == 0) QrTab(vm) else CookieTab(vm)
        }
    }
}

@Composable
private fun QrTab(vm: LoginViewModel) {
    val qr by vm.qr.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = qr) {
                is QrUi.Ready -> {
                    val bitmap = remember(s.url) { qrBitmap(s.url, 480) }
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = "登录二维码", modifier = Modifier.fillMaxSize())
                    } else {
                        Text("二维码渲染失败，请刷新", color = MaterialTheme.colorScheme.error)
                    }
                }
                is QrUi.Generating, is QrUi.Idle -> CircularProgressIndicator()
                is QrUi.Success ->
                    Text("扫码成功，正在进入…", color = MaterialTheme.colorScheme.primary)
                is QrUi.Failed ->
                    Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(16.dp))
        when (val s = qr) {
            is QrUi.Ready -> Text(
                "用哔哩哔哩 App 扫码 · ${s.secondsLeft} 秒后过期",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is QrUi.Generating, is QrUi.Idle -> Text("正在生成二维码…")
            is QrUi.Success -> Text("登录成功")
            is QrUi.Failed -> {
                Button(onClick = { vm.startQr() }) { Text("刷新二维码") }
                Spacer(Modifier.height(8.dp))
                Text("扫不上？可能被风控（412），换个网络再试，或改用 Cookie 导入", textAlign = TextAlign.Center)
            }
        }
        if (qr is QrUi.Ready) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.startQr() }) { Text("刷新二维码") }
        }
    }
}

@Composable
private fun CookieTab(vm: LoginViewModel) {
    val cookie by vm.cookie.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "从已登录的浏览器复制 Cookie（需包含 SESSDATA 与 DedeUserID），粘贴后自动验证，凭证加密存本机。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            placeholder = { Text("SESSDATA=xxx; bili_jct=yyy; DedeUserID=zzz") },
            maxLines = 8,
        )
        if (cookie is CookieUi.Failed) {
            Text((cookie as CookieUi.Failed).message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cookie is CookieUi.Verifying) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(4.dp))
                Text("正在验证…", modifier = Modifier.align(Alignment.CenterVertically))
            } else {
                Button(onClick = { vm.loginWithCookies(text) }) { Text("保存并验证") }
            }
        }
    }
}

/** ZXing 生成二维码位图；失败返回 null（调用方展示刷新）。 */
private fun qrBitmap(url: String, sizePx: Int): ImageBitmap? = try {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[y * sizePx + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
} catch (_: Exception) {
    null
}
