package cn.debubu.tingbili.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel()
) {
    val step by vm.stepSec.collectAsStateWithLifecycle()
    val imageFormat by vm.imageFormat.collectAsStateWithLifecycle()
    val cacheMb by vm.cacheSizeMb.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val customThemeColor by vm.customThemeColor.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = { TingBiliTopAppBar(title = "设置") }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ThemeColorSettings(
                themeMode = themeMode,
                customThemeColor = customThemeColor,
                onThemeModeChange = vm::setThemeMode,
                onCustomColorChange = vm::setCustomThemeColor
            )

            // 步进
            Column {
                Text("前进 / 后退步长: $step 秒", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = step.toFloat(),
                    onValueChange = { vm.setStep(it.toInt()) },
                    valueRange = 5f..60f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60).forEach { v ->
                        Button(onClick = { vm.setStep(v) }) { Text("${v}s") }
                    }
                }
            }

            // 图片格式
            Column {
                Text("B 站图片格式", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageFormat.entries.forEach { format ->
                        FilterChip(
                            selected = imageFormat == format,
                            onClick = { vm.setImageFormat(format) },
                            label = {
                                Text(
                                    when (format) {
                                        ImageFormat.AVIF -> "AVIF 省流量"
                                        ImageFormat.WEBP -> "WebP 均衡"
                                        ImageFormat.JPEG -> "JPEG 兜底"
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "默认 AVIF；若遇到图片无法显示，可切换 WebP 或 JPEG。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // 定时预设
            Column {
                Text("定时预设（分钟）", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { m ->
                        Button(onClick = { /* timer preset handled via PlayerManager in real app */ }) {
                            Text("${m}分")
                        }
                    }
                }
            }

            // 音频缓存
            Column {
                Text("音频缓存", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "已缓存约 ${cacheMb}MB / 500MB，上次播放的音频会本地缓存，二次播放秒开且省流量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.refreshCacheSize() }) { Text("刷新") }
                    Button(onClick = { vm.clearCache() }) { Text("清空缓存") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "预留登录入口 (二期)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text("登录后可导入 B 站收藏夹为收藏", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThemeColorSettings(
    themeMode: ThemeMode,
    customThemeColor: Int,
    onThemeModeChange: (ThemeMode) -> Unit,
    onCustomColorChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val themePrimary = MaterialTheme.colorScheme.primary
    val systemColor = remember(context, darkTheme, themePrimary) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            scheme.primary
        } else {
            themePrimary
        }
    }
    var showCustomDialog by remember { mutableStateOf(false) }

    Column {
        Text("主题色", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                val swatch = when (mode) {
                    ThemeMode.DEFAULT -> Color(PreferencesRepository.DEFAULT_THEME_COLOR)
                    ThemeMode.SYSTEM -> systemColor
                    ThemeMode.CUSTOM -> Color(customThemeColor)
                }
                ThemeModeChip(
                    mode = mode,
                    swatch = swatch,
                    selected = themeMode == mode,
                    onClick = {
                        when (mode) {
                            ThemeMode.CUSTOM -> showCustomDialog = true
                            else -> onThemeModeChange(mode)
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when (themeMode) {
            ThemeMode.DEFAULT -> {
                Text(
                    "默认听视频粉 #FF6699",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ThemeMode.SYSTEM -> {
                Text(
                    "跟随系统动态取色，当前系统主色 ${systemColor.toHex()}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ThemeMode.CUSTOM -> {
                Text(
                    "当前自定义色 ${Color(customThemeColor).toHex()}，点击“自定义”可重新调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showCustomDialog) {
        CustomColorDialog(
            initialColor = customThemeColor,
            onConfirm = {
                onCustomColorChange(it)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false }
        )
    }
}

@Composable
private fun ThemeModeChip(
    mode: ThemeMode,
    swatch: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = { ColorSwatch(swatch) },
        label = { Text(mode.label()) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = swatch.copy(alpha = 0.10f),
            selectedContainerColor = swatch.copy(alpha = 0.24f)
        )
    )
}

@Composable
private fun ColorSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

@Composable
private fun CustomColorDialog(
    initialColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var previewColor by remember(initialColor) { mutableStateOf(Color(initialColor)) }
    val hsv = remember(previewColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(previewColor.toArgb(), it) }
    }
    val updateColor: (Int, Float) -> Unit = { index, value ->
        val next = hsv.copyOf()
        next[index] = value
        previewColor = Color(android.graphics.Color.HSVToColor(next))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义主题色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(previewColor)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = previewColor.toHex(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text("色相", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = hsv[0],
                    onValueChange = { updateColor(0, it) },
                    valueRange = 0f..360f
                )

                Text("饱和度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = hsv[1],
                    onValueChange = { updateColor(1, it) },
                    valueRange = 0f..1f
                )

                Text("亮度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = hsv[2],
                    onValueChange = { updateColor(2, it) },
                    valueRange = 0.15f..1f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(previewColor.toArgb()) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun Color.toHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.DEFAULT -> "默认"
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.CUSTOM -> "自定义"
}
