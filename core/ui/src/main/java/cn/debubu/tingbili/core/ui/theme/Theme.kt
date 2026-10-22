package cn.debubu.tingbili.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ThemeMode

/** 当前生效的深色模式（含用户手动覆盖），供各页面做明暗分支，替代 isSystemInDarkTheme() */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun TingBiliTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = ThemeMode.DEFAULT,
    customThemeColorArgb: Int = PreferencesRepository.DEFAULT_THEME_COLOR,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // 十几次 blend/contentColor 全是纯计算：HSV 拖动每帧都重算，remember 按输入缓存
    val scheme = remember(themeMode, darkTheme, customThemeColorArgb) {
        when {
            themeMode == ThemeMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            themeMode == ThemeMode.CUSTOM -> brandedColorScheme(Color(customThemeColorArgb), darkTheme)
            else -> brandedColorScheme(Color(PreferencesRepository.DEFAULT_THEME_COLOR), darkTheme)
        }
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}

internal fun brandedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val primary = if (dark) blend(seed, Color.White, 0.12f) else seed
    val onPrimary = contentColor(primary)
    val primaryContainer = if (dark) {
        blend(seed, Color.Black, 0.58f)
    } else {
        blend(seed, Color.White, 0.72f)
    }
    val secondary = if (dark) blend(seed, Color.White, 0.32f) else blend(seed, Color.Black, 0.18f)
    val secondaryContainer = if (dark) {
        blend(seed, Color.Black, 0.66f)
    } else {
        blend(seed, Color.White, 0.82f)
    }
    val tertiary = blend(primary, Color(0xFF53B8FF), 0.28f)

    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = contentColor(primaryContainer),
        secondary = secondary,
        onSecondary = contentColor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = contentColor(secondaryContainer),
        tertiary = tertiary,
        onTertiary = contentColor(tertiary),
        surfaceTint = primary,
        inversePrimary = primary
    )
}

private fun contentColor(background: Color): Color {
    return if (background.luminance() > 0.5f) Color.Black else Color.White
}

private fun blend(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f
    )
}
