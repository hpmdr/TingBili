package cn.debubu.tingbili.feature.mine

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import cn.debubu.tingbili.core.data.model.DarkMode
import cn.debubu.tingbili.core.data.model.DarkThemeStyle
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.theme.LocalDarkTheme
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/** 二级页：外观设置（深色模式 / 深色风格 / 主题色）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: AppearanceViewModel = hiltViewModel()
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
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                // 底部留出悬浮 mini 的高度
                .padding(bottom = cn.debubu.tingbili.core.ui.MiniPlayerClearance)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                SettingSectionLabel("深色模式")
                SettingsGroup {
                    SettingRadioRow(
                        title = "跟随系统",
                        subtitle = "跟随系统明暗自动切换",
                        selected = darkMode == DarkMode.FOLLOW_SYSTEM,
                        onClick = { vm.setDarkMode(DarkMode.FOLLOW_SYSTEM) }
                    )
                    SettingDivider()
                    SettingRadioRow(
                        title = "浅色",
                        subtitle = "始终使用浅色界面",
                        selected = darkMode == DarkMode.LIGHT,
                        onClick = { vm.setDarkMode(DarkMode.LIGHT) }
                    )
                    SettingDivider()
                    SettingRadioRow(
                        title = "深色",
                        subtitle = "始终使用深色界面",
                        selected = darkMode == DarkMode.DARK,
                        onClick = { vm.setDarkMode(DarkMode.DARK) }
                    )
                }
            }

            // 浅色下不展示：之前置灰占位反而让人困惑
            if (darkMode != DarkMode.LIGHT) {
                Column {
                    SettingSectionLabel("深色风格")
                    SettingsGroup {
                        SettingRadioRow(
                            title = "深灰",
                            subtitle = "层级柔和，默认",
                            selected = darkThemeStyle == DarkThemeStyle.DARK_GRAY,
                            onClick = { vm.setDarkThemeStyle(DarkThemeStyle.DARK_GRAY) }
                        )
                        SettingDivider()
                        SettingRadioRow(
                            title = "纯黑",
                            subtitle = "OLED 像素不发光，深夜不晃眼",
                            selected = darkThemeStyle == DarkThemeStyle.PURE_BLACK,
                            onClick = { vm.setDarkThemeStyle(DarkThemeStyle.PURE_BLACK) }
                        )
                    }
                }
            }

            ThemeColorSettings(
                themeMode = themeMode,
                customThemeColor = customThemeColor,
                onThemeModeChange = vm::setThemeMode,
                onCustomColorChange = vm::setCustomThemeColor
            )
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
    val darkTheme = LocalDarkTheme.current
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
        SettingSectionLabel("主题色")
        SettingsGroup {
            SettingRadioRow(
                title = "默认",
                subtitle = "听视频粉 ${Color(PreferencesRepository.DEFAULT_THEME_COLOR).toHex()}",
                selected = themeMode == ThemeMode.DEFAULT,
                onClick = { onThemeModeChange(ThemeMode.DEFAULT) },
                trailing = { ColorSwatch(Color(PreferencesRepository.DEFAULT_THEME_COLOR)) }
            )
            SettingDivider()
            SettingRadioRow(
                title = "跟随系统",
                subtitle = "动态取色，当前系统主色 ${systemColor.toHex()}",
                selected = themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                trailing = { ColorSwatch(systemColor) }
            )
            SettingDivider()
            SettingRadioRow(
                title = "自定义",
                subtitle = "当前 ${Color(customThemeColor).toHex()}，点选重选颜色",
                selected = themeMode == ThemeMode.CUSTOM,
                onClick = { showCustomDialog = true },
                trailing = { ColorSwatch(Color(customThemeColor)) }
            )
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
private fun ColorSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
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