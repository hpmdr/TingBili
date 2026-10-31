package cn.debubu.tingbili.feature.mine

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 登录页：扫码（默认/主路径）+ 短信 + 密码 + Cookie 导入（兜底）。
 *
 * 版式：品牌价值卡 → 单张表单卡（方式切换 + 表单 + 主按钮 + 提示）→ 本地凭证说明。
 * 卡片最大 520dp 居中，横屏与平板不拉通栏；键盘弹出时按钮跟随 imePadding 上移。
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LoginHero()
                    LoginCard {
                        LoginMethodSwitch(selected = tab, onSelect = { tab = it })
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
}

// ── 页头：为什么登录 ──────────────────────────────────

@Composable
private fun LoginHero() {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val brush = Brush.linearGradient(
        colors = listOf(
            scheme.primary.copy(alpha = 0.16f),
            scheme.primary.copy(alpha = 0.04f),
            Color.Transparent,
        ),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .border(1.dp, scheme.primary.copy(alpha = 0.12f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(scheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "登录解锁高音质与字幕",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "同步收藏 · 历史 · 稍后再看",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
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
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LOGIN_METHODS.forEach { method ->
                val isSelected = selected == method.index
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) scheme.primary else Color.Transparent,
                    contentColor = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(method.index) },
                            role = Role.Tab,
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            method.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = method.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── 表单卡片骨架 ──────────────────────────────────────

@Composable
private fun LoginCard(content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
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

/** 统一错误条：比裸露红字更好定位，也不打断阅读节奏。 */
@Composable
private fun ErrorNotice(message: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.errorContainer)
            .border(1.dp, scheme.error.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = scheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onErrorContainer,
        )
    }
}

@Composable
private fun HelperText(text: String, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = if (center) Modifier.fillMaxWidth() else Modifier,
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
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LocalOnlyHint() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "凭证仅加密保存在本机",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "第三方非官方客户端 · AGPLv3 开源",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/** 表单输入的统一外观：填充容器 + 14dp 圆角，跟随主题明暗。 */
@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    cursorColor = MaterialTheme.colorScheme.primary,
)

// ── 扫码 ────────────────────────────────────────────

@Composable
private fun QrContent(vm: LoginViewModel) {
    val qr by vm.qr.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 二维码永远黑码白底，所以这里固定白卡，不跟随深色
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEAEAEA)),
            modifier = Modifier.size(208.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (val state = qr) {
                    is QrUi.Ready -> {
                        val bitmap = remember(state.url) { qrBitmap(state.url, 480) }
                        if (bitmap != null) {
                            Image(
                                bitmap,
                                contentDescription = "登录二维码",
                                modifier = Modifier.fillMaxSize(),
                            )
                            // 中心品牌标记；生成时已用 H 级纠错，遮挡不影响识别
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Headphones,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            Text(
                                "二维码渲染失败，请刷新",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB3261E),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    is QrUi.Generating, is QrUi.Idle -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = Color(0xFF9AA0A6),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "正在生成二维码…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280),
                        )
                    }

                    is QrUi.Success -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF1E8E3E),
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "扫码成功",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E8E3E),
                        )
                        Text(
                            "正在进入…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280),
                        )
                    }

                    is QrUi.Failed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = Color(0xFFB3261E),
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB3261E),
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        when (val state = qr) {
            is QrUi.Ready -> {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { state.secondsLeft / LoginViewModel.QR_TTL_SEC.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHigh,
                    strokeCap = StrokeCap.Round,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "有效期剩余 ${state.secondsLeft}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { vm.startQr() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("刷新", style = MaterialTheme.typography.labelMedium)
                    }
                }
                state.notice?.let {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.WifiOff,
                            contentDescription = null,
                            tint = scheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.tertiary,
                        )
                    }
                }
                Text(
                    "使用哔哩哔哩 App 扫码登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            is QrUi.Generating, is QrUi.Idle -> Unit

            is QrUi.Success -> Unit

            is QrUi.Failed -> {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.startQr() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("刷新二维码", style = MaterialTheme.typography.labelLarge)
                }
                HelperText(
                    "扫不上？可能被风控（412），换个网络再试，或改用 Cookie 导入",
                    center = true,
                )
            }
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = zone,
                onValueChange = { zone = it.filter(Char::isDigit).take(4) },
                modifier = Modifier.width(96.dp),
                label = { Text("区号") },
                prefix = { Text("+") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = loginFieldColors(),
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
                colors = loginFieldColors(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { codeFocus.requestFocus() }),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(codeFocus),
                label = { Text("验证码") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = loginFieldColors(),
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
            FilledTonalButton(
                onClick = { vm.sendSms(zone.toIntOrNull() ?: 86, tel) },
                enabled = canSend,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 108.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    text = when {
                        sending -> "发送中"
                        sent != null && sent.secondsLeft > 0 -> "${sent.secondsLeft}s 后重发"
                        else -> "获取验证码"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.filter { c -> !c.isWhitespace() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("手机号 / 邮箱") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = loginFieldColors(),
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
            colors = loginFieldColors(),
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CookieHelp()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Cookie 字符串",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    clipboard?.primaryClip?.getItemAt(0)
                        ?.coerceToText(context)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { text = it }
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    Icons.Filled.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("粘贴", style = MaterialTheme.typography.labelMedium)
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Cookie 字符串" },
            placeholder = { Text("SESSDATA=xxx; bili_jct=yyy; DedeUserID=zzz") },
            minLines = 4,
            maxLines = 6,
            shape = RoundedCornerShape(14.dp),
            colors = loginFieldColors(),
            supportingText = { Text("整段粘贴，不要只复制其中几项，少一项就会登录失败") },
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
        HelperText("手机上取不到，需要在电脑上复制后发到手机粘贴")
    }
}

@Composable
private fun CookieHelp() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.55f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "如何获取 Cookie",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        CookieStep("1", "电脑打开 bilibili.com 并登录")
        CookieStep("2", "按 F12 → Network（网络）→ 刷新页面，点任意一条请求")
        CookieStep("3", "在「请求标头」里找到 Cookie，右键 → 复制值")
    }
}

@Composable
private fun CookieStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$number.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ZXing 生成二维码位图；H 级纠错保证中心品牌标记不破坏识别。失败返回 null。 */
private fun qrBitmap(url: String, sizePx: Int): ImageBitmap? = try {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H)
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
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
