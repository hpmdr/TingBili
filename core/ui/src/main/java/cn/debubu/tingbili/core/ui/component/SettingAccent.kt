package cn.debubu.tingbili.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cn.debubu.tingbili.core.ui.theme.LocalDarkTheme

data class SettingAccentColors(
    val tint: Color,
    val background: Color,
)

/** 设置行图标色板：页面只传语义色，浅色/深色容器在这里统一维护。 */
enum class SettingAccent {
    PINK,
    GREEN,
    AMBER,
    PURPLE,
    INDIGO,
    CYAN,
    BLUE,
    DANGER;

    @Composable
    fun colors(): SettingAccentColors {
        val dark = LocalDarkTheme.current
        return when (this) {
            PINK -> SettingAccentColors(
                tint = if (dark) Color(0xFFFF8EAC) else Color(0xFFB80C42),
                background = if (dark) Color(0xFF3B1220) else Color(0xFFFFF0F5),
            )
            GREEN -> SettingAccentColors(
                tint = if (dark) Color(0xFF5ED6A6) else Color(0xFF067A55),
                background = if (dark) Color(0xFF062B22) else Color(0xFFECFDF5),
            )
            AMBER -> SettingAccentColors(
                tint = if (dark) Color(0xFFFFC55A) else Color(0xFF9A5B00),
                background = if (dark) Color(0xFF2E2306) else Color(0xFFFFF7E6),
            )
            PURPLE -> SettingAccentColors(
                tint = if (dark) Color(0xFFC2A4FF) else Color(0xFF6941C6),
                background = if (dark) Color(0xFF231038) else Color(0xFFF3E8FF),
            )
            INDIGO -> SettingAccentColors(
                tint = if (dark) Color(0xFFAEB4FF) else Color(0xFF3943B7),
                background = if (dark) Color(0xFF1E1B4B) else Color(0xFFEEF2FF),
            )
            CYAN -> SettingAccentColors(
                tint = if (dark) Color(0xFF65D8ED) else Color(0xFF087D96),
                background = if (dark) Color(0xFF083344) else Color(0xFFECFEFF),
            )
            BLUE -> SettingAccentColors(
                tint = if (dark) Color(0xFF7DD3FC) else Color(0xFF0369A1),
                background = if (dark) Color(0xFF0C2A3A) else Color(0xFFE8F6FD),
            )
            DANGER -> SettingAccentColors(
                tint = MaterialTheme.colorScheme.error,
                background = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            )
        }
    }
}
