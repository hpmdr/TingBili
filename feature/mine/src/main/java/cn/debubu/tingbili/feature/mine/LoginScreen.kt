package cn.debubu.tingbili.feature.mine

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 登录页：扫码（默认/主路径）+ 短信 + 密码 + Cookie 导入（兜底）。
 *
 * 版式：价值说明行 → 图标分段控件选方式 → 统一表单卡片（标题/说明/表单/主按钮/提示）。
 * 卡片尽量窄（520dp）居中，横屏与平板不拉成通栏；键盘弹出时按钮跟随 imePadding 上移。
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
    var tab by remember { mutableIntStateOf(QR_TAB) }
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
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LoginIntro()
                LoginMethodSwitch(selected = tab, onSelect = { tab = it })
                LoginCard {
                    when (tab) {
                        QR_TAB -> QrContent(vm)
                        SMS_TAB -> SmsContent(vm)
                        PASSWORD_TAB -> PasswordContent(vm)
                        else -> CookieContent(vm)
                    }
                }
                LocalOnlyHint()
            }
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

// ── 页头：为什么登录 ──────────────────────────────────

@Composable
private fun LoginIntro() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "登录解锁高音质与字幕",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "同步收藏 · 历史 · 稍后听",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ── 登录方式切换 ──────────────────────────────────────

private data class LoginMethod(
    val label: String,
    val icon: ImageVector,
    val index: Int,
)

private val LOGIN_METHODS = listOf(
    LoginMethod("扫码", Icons.Filled.QrCodeScanner, QR_TAB),
    LoginMethod("短信", Icons.Filled.Sms, SMS_TAB),
    LoginMethod("密码", Icons.Filled.Key, PASSWORD_TAB),
    LoginMethod("Cookie", Icons.Filled.Cookie, COOKIE_TAB),
)

@Composable
private fun LoginMethodSwitch(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        LOGIN_METHODS.forEachIndexed { position, method ->
            SegmentedButton(
                selected = selected == method.index,
                onClick = { onSelect(method.index) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = position,
                    count = LOGIN_METHODS.size,
                ),
                icon = {
                    Icon(
                        method.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = {
                    Text(
                        text = method.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

// ── 表单卡片骨架 ──────────────────────────────────────

@Composable
private fun LoginCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun ModeHeader(title: String, description: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 统一错误条：比裸露红字更好定位，也不打断阅读节奏。 */
@Composable
private fun ErrorNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PrimaryActionButton(
    text: String,
    loading: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(14.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
private fun LocalOnlyHint() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "凭证仅加密保存在本机",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 扫码 ────────────────────────────────────────────

@Composable
private fun QrContent(vm: LoginViewModel) {
    val qr by vm.qr.collectAsStateWithLifecycle()
    ModeHeader("扫码登录", "打开哔哩哔哩 App，用右上角扫一扫")
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // 二维码永远黑码白底，所以这里固定白卡，不跟随深色
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
        ) {
            Box(
                modifier = Modifier.size(224.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = qr) {
                    is QrUi.Ready -> {
                        val bitmap = remember(state.url) { qrBitmap(state.url, 480) }
                        if (bitmap != null) {
                            Image(
                                bitmap,
                                contentDescription = "登录二维码",
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                            )
                        } else {
                            Text(
                                "二维码渲染失败，请刷新",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB3261E),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    is QrUi.Generating, is QrUi.Idle -> CircularProgressIndicator()
                    is QrUi.Success -> Text(
                        "扫码成功",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC2185B),
                    )
                    is QrUi.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3261E),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
    when (val state = qr) {
        is QrUi.Ready -> {
            LinearProgressIndicator(
                progress = { state.secondsLeft / LoginViewModel.QR_TTL_SEC.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${state.secondsLeft} 秒后过期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { vm.startQr() }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }
            state.notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        is QrUi.Generating, is QrUi.Idle -> Text(
            "正在生成二维码…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        is QrUi.Success -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("扫码成功，正在进入…", color = MaterialTheme.colorScheme.primary)
        }
        is QrUi.Failed -> {
            Button(
                onClick = { vm.startQr() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("刷新二维码")
            }
            HelperText("扫不上？可能被风控（412），换个网络再试，或改用 Cookie 导入")
        }
    }
}

// ── 短信 ────────────────────────────────────────────

@Composable
private fun SmsContent(vm: LoginViewModel) {
    val sms by vm.sms.collectAsStateWithLifecycle()
    var zone by remember { mutableStateOf("86") }
    var tel by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val codeFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // 发送成功（手机号已过校验）→ 光标自动进验证码框
    val sentKey = (sms as? LoginViewModel.SmsUi.Sent)?.captchaKey
    LaunchedEffect(sentKey) {
        if (!sentKey.isNullOrBlank()) codeFocus.requestFocus()
    }
    val sending = sms is LoginViewModel.SmsUi.Sending
    val submitting = sms is LoginViewModel.SmsUi.Submitting
    val sent = sms as? LoginViewModel.SmsUi.Sent
    val canSend = !sending && !submitting && (sent == null || sent.secondsLeft <= 0)

    ModeHeader("短信登录", "输入手机号，接收验证码后登录")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = zone,
            onValueChange = { zone = it.filter(Char::isDigit).take(4) },
            modifier = Modifier.width(84.dp),
            label = { Text("区号") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { codeFocus.requestFocus() }),
        )
        OutlinedTextField(
            value = tel,
            onValueChange = { tel = it.filter(Char::isDigit).take(15) },
            modifier = Modifier.weight(1f),
            label = { Text("手机号") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { codeFocus.requestFocus() }),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(codeFocus),
            label = { Text("验证码") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    vm.submitSms(zone.toIntOrNull() ?: 86, tel, code)
                },
            ),
        )
        TextButton(
            onClick = { vm.sendSms(zone.toIntOrNull() ?: 86, tel) },
            enabled = canSend,
        ) {
            Text(
                when {
                    sending -> "发送中"
                    sent != null && sent.secondsLeft > 0 -> "${sent.secondsLeft}s 后重发"
                    else -> "获取验证码"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    if (sms is LoginViewModel.SmsUi.Failed) {
        ErrorNotice((sms as LoginViewModel.SmsUi.Failed).message)
    }
    PrimaryActionButton(
        text = if (submitting) "登录中" else "登录",
        loading = submitting,
        enabled = !sending,
        onClick = {
            focusManager.clearFocus()
            vm.submitSms(zone.toIntOrNull() ?: 86, tel, code)
        },
    )
    HelperText("验证码 5 分钟内有效；发送前需先完成安全验证，风控期可换扫码或 Cookie")
}

// ── 密码 ────────────────────────────────────────────

@Composable
private fun PasswordContent(vm: LoginViewModel) {
    val passwordUi by vm.password.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val working = passwordUi is LoginViewModel.PasswordUi.Working

    ModeHeader("密码登录", "使用哔哩哔哩账号与密码登录")
    OutlinedTextField(
        value = username,
        onValueChange = { username = it.filter { c -> !c.isWhitespace() } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("手机号 / 邮箱") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it.filter { c -> !c.isWhitespace() } },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("密码") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                vm.submitPassword(username, password)
            },
        ),
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
        ErrorNotice((passwordUi as LoginViewModel.PasswordUi.Failed).message)
    }
    PrimaryActionButton(
        text = if (working) "登录中" else "登录",
        loading = working,
        onClick = {
            focusManager.clearFocus()
            vm.submitPassword(username, password)
        },
    )
    HelperText("密码经 RSA 加密后传输，明文不出本机；触发风控时请改用扫码或 Cookie")
}

// ── Cookie 导入 ──────────────────────────────────────

@Composable
private fun CookieContent(vm: LoginViewModel) {
    val cookie by vm.cookie.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(android.content.ClipboardManager::class.java)
    }
    val verifying = cookie is CookieUi.Verifying

    ModeHeader("Cookie 导入", "从已登录的浏览器整段复制 Cookie，粘贴后自动验证")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HelperText("1. F12 → Network → 任意 bilibili.com 请求")
        HelperText("2. Request Headers → Cookie → 右键 Copy value")
        HelperText("3. 整段粘贴到下面即可，不需要逐条抄")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("SESSDATA=xxx; bili_jct=yyy; DedeUserID=zzz") },
        minLines = 4,
        maxLines = 6,
        shape = RoundedCornerShape(14.dp),
        trailingIcon = {
            TextButton(
                onClick = {
                    clipboard?.primaryClip?.getItemAt(0)
                        ?.coerceToText(context)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { text = it }
                },
            ) { Text("粘贴") }
        },
        supportingText = { Text("SESSDATA 是 HttpOnly，document.cookie 里没有；也可在 Application → Cookies 单独复制") },
    )
    if (cookie is CookieUi.Failed) {
        ErrorNotice((cookie as CookieUi.Failed).message)
    }
    PrimaryActionButton(
        text = if (verifying) "正在验证…" else "保存并验证",
        loading = verifying,
        onClick = {
            focusManager.clearFocus()
            vm.loginWithCookies(text)
        },
    )
    HelperText("必填 SESSDATA，bili_jct / DedeUserID 有就一起带上。手机上没开发者工具，在电脑复制后发到手机粘贴")
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

private const val QR_TAB = 0
private const val SMS_TAB = 1
private const val PASSWORD_TAB = 2
private const val COOKIE_TAB = 3
