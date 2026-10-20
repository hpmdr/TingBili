package cn.debubu.tingbili.feature.mine

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
    var tab by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        onDispose { vm.stopQr() }
    }

    // 扫码取码统一走这里（初次进入/切回），避免重复生成浪费请求
    // 切走扫码页即停轮询（省请求、防风控）；切回 resumeQr：有效续跑、失效重取
    LaunchedEffect(tab, autoStartQr) {
        if (!autoStartQr) return@LaunchedEffect
        if (tab == QR_TAB) {
            vm.resumeQr()
        } else {
            vm.stopQr()
        }
    }
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
                Tab(selected = tab == PASSWORD_TAB, onClick = { tab = PASSWORD_TAB }, text = { Text("密码登录") })
                Tab(selected = tab == SMS_TAB, onClick = { tab = SMS_TAB }, text = { Text("短信登录") })
                Tab(selected = tab == QR_TAB, onClick = { tab = QR_TAB }, text = { Text("扫码登录") })
                Tab(selected = tab == COOKIE_TAB, onClick = { tab = COOKIE_TAB }, text = { Text("Cookie 导入") })
            }
            when (tab) {
                PASSWORD_TAB -> PasswordTab(vm)
                SMS_TAB -> SmsTab(vm)
                QR_TAB -> QrTab(vm)
                else -> CookieTab(vm)
            }
            val gee by vm.gee.collectAsStateWithLifecycle()
            (gee as? LoginViewModel.GeeUi.Showing)?.let { showing ->
                GeetestDialog(
                    gt = showing.gt,
                    challenge = showing.challenge,
                    onSuccess = { validate, seccode, challenge ->
                        vm.onGeeResult(validate, seccode, challenge)
                    },
                    onCancel = { vm.onGeeCancel() },
                )
            }
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
            is QrUi.Ready -> {
                Text(
                    "用哔哩哔哩 App 扫码 · ${s.secondsLeft} 秒后过期",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.notice != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
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

@Composable
private fun SmsTab(vm: LoginViewModel) {
    val sms by vm.sms.collectAsStateWithLifecycle()
    var zone by remember { mutableStateOf("86") }
    var tel by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val codeFocus = remember { FocusRequester() }
    // 发送成功（手机号已过校验）→ 光标自动进验证码框
    val sentKey = (sms as? LoginViewModel.SmsUi.Sent)?.captchaKey
    LaunchedEffect(sentKey) {
        if (!sentKey.isNullOrBlank()) codeFocus.requestFocus()
    }
    val sending = sms is LoginViewModel.SmsUi.Sending
    val submitting = sms is LoginViewModel.SmsUi.Submitting
    val sent = sms as? LoginViewModel.SmsUi.Sent
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = zone,
                onValueChange = { zone = it.filter(Char::isDigit).take(4) },
                modifier = Modifier.width(88.dp),
                label = { Text("区号") },
                singleLine = true,
            )
            OutlinedTextField(
                value = tel,
                onValueChange = { tel = it.filter(Char::isDigit).take(15) },
                modifier = Modifier.weight(1f),
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(codeFocus),
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val canSend = !sending && !submitting && (sent == null || sent.secondsLeft <= 0)
            Button(
                onClick = { vm.sendSms(zone.toIntOrNull() ?: 86, tel) },
                enabled = canSend,
            ) {
                Text(
                    when {
                        sending -> "发送中"
                        sent != null && sent.secondsLeft > 0 -> "${sent.secondsLeft}秒后重发"
                        else -> "获取验证码"
                    }
                )
            }
        }
        if (sms is LoginViewModel.SmsUi.Failed) {
            Text((sms as LoginViewModel.SmsUi.Failed).message, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { vm.submitSms(zone.toIntOrNull() ?: 86, tel, code) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !sending && !submitting,
        ) {
            if (submitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (submitting) "登录中" else "登录")
        }
        Text(
            "先过滑块再发短信（B 站要求）；验证码 5 分钟内有效。风控期可能收不到短信，可换扫码/Cookie。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PasswordTab(vm: LoginViewModel) {
    val passwordUi by vm.password.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val working = passwordUi is LoginViewModel.PasswordUi.Working
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.filter { c -> !c.isWhitespace() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("手机号 / 邮箱") },
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.filter { c -> !c.isWhitespace() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                    )
                }
            },
        )
        if (passwordUi is LoginViewModel.PasswordUi.Failed) {
            Text((passwordUi as LoginViewModel.PasswordUi.Failed).message, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { vm.submitPassword(username, password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !working,
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("登录")
        }
        Text(
            "密码经 RSA 加密后传输（明文不出本机）；先过滑块再提交。撞风控需二次验证时请改用扫码/Cookie。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PASSWORD_TAB = 0
private const val SMS_TAB = 1
private const val QR_TAB = 2
private const val COOKIE_TAB = 3
