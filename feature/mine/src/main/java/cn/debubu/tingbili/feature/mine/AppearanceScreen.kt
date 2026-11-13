package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.DarkMode
import cn.debubu.tingbili.core.data.model.DarkThemeStyle
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliBottomSheet
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.core.ui.theme.LocalDarkTheme
import cn.debubu.tingbili.core.ui.theme.LocalDarkAmoled
import cn.debubu.tingbili.core.ui.theme.brandedColorScheme

/** 二级页：外观设置（深色模式 / 深色风格 / 主题色）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: AppearanceViewModel = hiltViewModel(),
) {
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val darkMode by vm.darkMode.collectAsStateWithLifecycle()
    val darkThemeStyle by vm.darkThemeStyle.collectAsStateWithLifecycle()
    val customThemeColor by vm.customThemeColor.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "外观",
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = cn.debubu.tingbili.core.ui.MiniPlayerClearance)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                SettingSectionLabel("深色模式")
                SettingsGroup(modifier = Modifier.selectableGroup()) {
                    SettingRadioRow(
                        title = "跟随系统",
                        subtitle = "跟随系统明暗自动切换",
                        selected = darkMode == DarkMode.FOLLOW_SYSTEM,
                        onClick = { vm.setDarkMode(DarkMode.FOLLOW_SYSTEM) },
                    )
                    SettingDivider()
                    SettingRadioRow(
                        title = "浅色",
                        subtitle = "始终使用浅色界面",
                        selected = darkMode == DarkMode.LIGHT,
                        onClick = { vm.setDarkMode(DarkMode.LIGHT) },
                    )
                    SettingDivider()
                    SettingRadioRow(
                        title = "深色",
                        subtitle = "始终使用深色界面",
                        selected = darkMode == DarkMode.DARK,
                        onClick = { vm.setDarkMode(DarkMode.DARK) },
                    )
                }
            }

            if (darkMode != DarkMode.LIGHT) {
                Column {
                    SettingSectionLabel("深色风格")
                    SettingsGroup(modifier = Modifier.selectableGroup()) {
                        SettingRadioRow(
                            title = "深灰",
                            subtitle = "层级柔和，默认",
                            selected = darkThemeStyle == DarkThemeStyle.DARK_GRAY,
                            onClick = { vm.setDarkThemeStyle(DarkThemeStyle.DARK_GRAY) },
                        )
                        SettingDivider()
                        SettingRadioRow(
                            title = "纯黑",
                            subtitle = "OLED 像素不发光，深夜不晃眼",
                            selected = darkThemeStyle == DarkThemeStyle.PURE_BLACK,
                            onClick = { vm.setDarkThemeStyle(DarkThemeStyle.PURE_BLACK) },
                        )
                    }
                }
            }

            ThemeColorSettings(
                themeMode = themeMode,
                customThemeColor = customThemeColor,
                onThemeModeChange = vm::setThemeMode,
                onCustomColorChange = vm::setCustomThemeColor,
            )
        }
    }
}

@Composable
private fun ThemeColorSettings(
    themeMode: ThemeMode,
    customThemeColor: Int,
    onThemeModeChange: (ThemeMode) -> Unit,
    onCustomColorChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = LocalDarkTheme.current
    // minSdk 31 起动态取色恒可用，无需再判断 SDK_INT（lint ObsoleteSdkInt）
    val systemColor = remember(context, darkTheme) {
        val scheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        scheme.primary
    }
    var showCustomSheet by remember { mutableStateOf(false) }

    Column {
        SettingSectionLabel("主题色")
        SettingsGroup(modifier = Modifier.selectableGroup()) {
            SettingRadioRow(
                title = "默认",
                subtitle = "听视频粉 ${Color(PreferencesRepository.DEFAULT_THEME_COLOR).toHex()}",
                selected = themeMode == ThemeMode.DEFAULT,
                onClick = { onThemeModeChange(ThemeMode.DEFAULT) },
                trailing = { ColorSwatch(Color(PreferencesRepository.DEFAULT_THEME_COLOR)) },
            )
            SettingDivider()
            SettingRadioRow(
                title = "跟随系统",
                subtitle = "动态取色，当前系统主色 ${systemColor.toHex()}",
                selected = themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                trailing = { ColorSwatch(systemColor) },
            )
            SettingDivider()
            SettingRadioRow(
                title = "自定义",
                subtitle = "当前 ${Color(customThemeColor).toHex()}，点选重选颜色",
                selected = themeMode == ThemeMode.CUSTOM,
                onClick = { showCustomSheet = true },
                trailing = { ColorSwatch(Color(customThemeColor)) },
            )
        }
    }

    if (showCustomSheet) {
        CustomColorSheet(
            initialColor = customThemeColor,
            onApply = {
                onCustomColorChange(it)
                showCustomSheet = false
            },
            onDismiss = { showCustomSheet = false },
        )
    }
}

@Composable
private fun ColorSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun CustomColorSheet(
    initialColor: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val darkTheme = LocalDarkTheme.current
    val darkAmoled = LocalDarkAmoled.current
    // HSV 草稿：色相 0..360、饱和 0..1、明度钳在 0.15..1（过暗的 seed 整套 tonal 会崩，沿用旧下限）
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var brightness by remember(initialColor) { mutableStateOf(initialHsv[2].coerceAtLeast(0.15f)) }
    val previewColor = remember(hue, saturation, brightness) {
        Color(hsvToArgb(hue, saturation, brightness))
    }
    var hexText by remember(initialColor) { mutableStateOf(previewColor.toHex()) }
    var hexError by remember { mutableStateOf(false) }
    // 最近颜色只放内存：防“调乱了回不去”，不进 DataStore
    val recents = remember { mutableStateListOf<Int>() }
    // 手势同帧内写 state 读不到（要等下一帧重组）：抬手记最近颜色必须读这个同步 ref
    val draftHsv = remember(initialColor) { initialHsv.copyOf() }
    fun pushRecent(argb: Int) {
        recents.remove(argb)
        recents.add(0, argb)
        while (recents.size > 5) recents.removeAt(recents.lastIndex)
    }
    fun syncDraft(h: Float, sat: Float, v: Float) {
        hue = h
        saturation = sat
        brightness = v
        draftHsv[0] = h
        draftHsv[1] = sat
        draftHsv[2] = v
        hexText = Color(hsvToArgb(h, sat, v)).toHex()
        hexError = false
    }
    // 手势回调经 rememberUpdatedState 包一层：pointerInput(Unit) 常驻不重启，闭包永远读最新值
    val onSvPoint by rememberUpdatedState { p: Offset, size: Size ->
        syncDraft(
            hue,
            (p.x / size.width).coerceIn(0f, 1f),
            (0.15f + (1f - p.y / size.height) * 0.85f).coerceIn(0.15f, 1f),
        )
    }
    val onSvRelease by rememberUpdatedState { pushRecent(hsvToArgb(draftHsv[0], draftHsv[1], draftHsv[2])) }
    val onHuePoint by rememberUpdatedState { p: Offset, size: Size ->
        syncDraft((p.x / size.width * 360f).coerceIn(0f, 360f), saturation, brightness)
    }
    val onHueRelease by rememberUpdatedState { pushRecent(hsvToArgb(draftHsv[0], draftHsv[1], draftHsv[2])) }
    // 预览直接用落库同一套生成逻辑：所见即生效后的主题
    val scheme = remember(previewColor, darkTheme, darkAmoled) {
        brandedColorScheme(previewColor, darkTheme, darkAmoled)
    }
    // 取色板叠加层与 hue 无关：remember 常驻，拖动时 Draw 期只读不分配
    val svWhiteOverlay = remember { Brush.horizontalGradient(listOf(Color.White, Color.Transparent)) }
    val svBlackOverlay = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black)) }
    val hueRainbow = remember { Brush.horizontalGradient((0..6).map { Color(hsvToArgb(it * 60f, 1f, 1f)) }) }
    val svBaseColor = remember(hue) { Color(hsvToArgb(hue, 1f, 1f)) }

    TingBiliBottomSheet(
        title = "自定义主题色",
        subtitle = "拖动选色，下方实时预览整套主题；应用后更新整个界面。",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onApply(previewColor.toArgb()) }) { Text("应用") }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 整套 tonal 预览：主色 / 容器 / 点缀
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TonalPill(label = "主色", bg = scheme.primary, fg = scheme.onPrimary, hex = scheme.primary.toHex(), modifier = Modifier.weight(1f))
                TonalPill(label = "容器", bg = scheme.secondaryContainer, fg = scheme.onSecondaryContainer, hex = scheme.secondaryContainer.toHex(), modifier = Modifier.weight(1f))
                TonalPill(label = "点缀", bg = scheme.tertiary, fg = scheme.onTertiary, hex = scheme.tertiary.toHex(), modifier = Modifier.weight(1f))
            }
            // 二维板：横轴饱和、纵轴明度，底色随色相走
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .pickGesture(onPoint = onSvPoint, onRelease = onSvRelease)
                    .semantics { contentDescription = "饱和度和明度二维取色板" },
            ) {
                val w = size.width
                val h = size.height
                drawRect(svBaseColor)
                drawRect(svWhiteOverlay)
                drawRect(svBlackOverlay)
                val cx = saturation * w
                val cy = (1f - (brightness - 0.15f) / 0.85f) * h
                val center = Offset(cx, cy)
                drawCircle(previewColor, radius = 10.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 12.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                drawCircle(Color(0xFF333333), radius = 13.5.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            }
            // 色相彩虹条
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .pickGesture(onPoint = onHuePoint, onRelease = onHueRelease)
                    .semantics { contentDescription = "色相取色条" },
            ) {
                drawRect(hueRainbow)
                val cx = hue / 360f * size.width
                val center = Offset(cx, size.height / 2)
                drawCircle(Color.White, radius = 13.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                drawCircle(Color(0xFF333333), radius = 14.5.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            }
            // Hex 输入：输满 7 位才解析，避免输入过程中误报
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { input ->
                        val upper = input.uppercase().take(7)
                        hexText = upper
                        // 按有效位判：不带 # 输 6 位即完整（如 5B8DEF），避免 6 位无 # 卡住不解析
                        if (upper.trim().removePrefix("#").length < 6) {
                            hexError = false
                        } else {
                            val parsed = parseHexToHsv(upper)
                            if (parsed != null) {
                                syncDraft(parsed[0], parsed[1], parsed[2])
                                pushRecent(Color(hsvToArgb(parsed[0], parsed[1], parsed[2])).toArgb())
                            } else {
                                hexError = true
                            }
                        }
                    },
                    label = { Text("Hex") },
                    placeholder = { Text("#E91E63") },
                    singleLine = true,
                    isError = hexError,
                    supportingText = if (hexError) {
                        { Text("格式应为 #RRGGBB") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "十六进制颜色值" },
                )
                Spacer(Modifier.size(10.dp))
                ColorSwatch(previewColor)
            }
            if (recents.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "最近使用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recents.forEach { argb ->
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable {
                                        FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
                                            .let { syncDraft(it[0], it[1], it[2].coerceAtLeast(0.15f)) }
                                    }
                                    .semantics { contentDescription = "最近颜色 ${Color(argb).toHex()}" },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 点按+拖拽通用手势：消费位移避免跟 BottomSheet 滚动打架，抬手回调用来记“最近颜色”。 */
@Composable
private fun Modifier.pickGesture(
    onPoint: (Offset, Size) -> Unit,
    onRelease: () -> Unit,
): Modifier {
    // key 必须用 Unit 常驻：回调每帧都变，若拿回调当 key，拖动中 detector 会反复重启，
    // 手势被取消、尾段丢失、onRelease 永远不触发；用 rememberUpdatedState 在内部读最新值。
    val latestPoint = rememberUpdatedState(onPoint)
    val latestRelease = rememberUpdatedState(onRelease)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            latestPoint.value(down.position, size.toSize())
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
                event.changes.firstOrNull()?.position?.let { latestPoint.value(it, size.toSize()) }
            } while (event.changes.any { it.pressed })
            latestRelease.value()
        }
    }
}

@Composable
private fun TonalPill(
    label: String,
    bg: Color,
    fg: Color,
    hex: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        Text(hex, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

private fun hsvToArgb(h: Float, s: Float, v: Float): Int =
    android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0.15f, 1f)))

/** 解析 #RRGGBB（# 可省略），成功返回 HSV，失败返回 null。 */
private fun parseHexToHsv(input: String): FloatArray? {
    val clean = input.trim().removePrefix("#")
    if (!clean.matches(Regex("[0-9a-fA-F]{6}"))) return null
    val argb = android.graphics.Color.parseColor("#$clean")
    return FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
        .also { it[2] = it[2].coerceAtLeast(0.15f) }
}

private fun Color.toHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)
