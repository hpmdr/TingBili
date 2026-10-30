package cn.debubu.tingbili.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class SleepTimerInputTest {

    @Test
    fun `presets start at 30 and cover common durations`() {
        assertEquals(listOf(30, 60, 90, 120, 240), SLEEP_PRESETS)
    }

    @Test
    fun `custom range is 30 to 600`() {
        assertEquals(30, SLEEP_MIN_MIN)
        assertEquals(600, SLEEP_MAX_MIN)
    }

    @Test
    fun `blank and non-numeric input are errors`() {
        assertEquals("请输入分钟数", sleepCustomError(""))
        assertEquals("请输入分钟数", sleepCustomError("   "))
        assertEquals("请输入数字", sleepCustomError("ab"))
    }

    @Test
    fun `out of range input is error`() {
        assertEquals("请输入 30~600 分钟", sleepCustomError("29"))
        assertEquals("请输入 30~600 分钟", sleepCustomError("601"))
    }

    @Test
    fun `arbitrary integers in range are valid`() {
        assertNull(sleepCustomError("30"))
        assertNull(sleepCustomError("45"))
        assertNull(sleepCustomError("600"))
    }
    @Test
    fun `end clock adds minutes to now`() {
        assertEquals("23:35", sleepEndClockLabel(60, LocalTime.of(22, 35)))
    }

    @Test
    fun `end clock wraps past midnight`() {
        assertEquals("00:20", sleepEndClockLabel(30, LocalTime.of(23, 50)))
    }
}
