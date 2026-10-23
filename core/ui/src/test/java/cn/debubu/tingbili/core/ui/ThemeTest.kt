package cn.debubu.tingbili.core.ui

import androidx.compose.ui.graphics.Color
import cn.debubu.tingbili.core.ui.theme.applyAmoledBlack
import cn.debubu.tingbili.core.ui.theme.brandedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `dark theme uses pure black background for OLED`() {
        val scheme = brandedColorScheme(Color(0xFFFF6699), dark = true)

        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
        assertEquals(Color.Black, scheme.surfaceDim)
        assertEquals(Color.Black, scheme.surfaceContainerLowest)
        assertEquals(Color(0xFF0A0A0A), scheme.surfaceContainerLow)
        assertEquals(Color(0xFF121212), scheme.surfaceContainer)
        assertEquals(Color(0xFF1A1A1A), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF242424), scheme.surfaceContainerHighest)
    }

    @Test
    fun `light theme keeps non-black background`() {
        val scheme = brandedColorScheme(Color(0xFFFF6699), dark = false)

        assertNotEquals(Color.Black, scheme.background)
    }

    @Test
    fun `applyAmoledBlack keeps primary but blacks background`() {
        val light = brandedColorScheme(Color(0xFFFF6699), dark = false)
        val blacked = applyAmoledBlack(light)

        assertEquals(light.primary, blacked.primary)
        assertEquals(Color.Black, blacked.background)
        assertEquals(Color.Black, blacked.surface)
    }
}
