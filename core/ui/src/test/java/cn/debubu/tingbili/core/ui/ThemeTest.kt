package cn.debubu.tingbili.core.ui

import androidx.compose.ui.graphics.Color
import cn.debubu.tingbili.core.ui.theme.brandedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test
    fun `default theme uses TingBili pink as primary`() {
        val scheme = brandedColorScheme(Color(0xFFFF6699), dark = false)

        assertEquals(Color(0xFFFF6699), scheme.primary)
    }

    @Test
    fun `custom theme uses custom seed as primary`() {
        val custom = Color(0xFF00A1D6)
        val scheme = brandedColorScheme(custom, dark = false)

        assertEquals(custom, scheme.primary)
    }
}
