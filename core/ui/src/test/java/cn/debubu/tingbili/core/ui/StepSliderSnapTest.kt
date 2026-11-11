package cn.debubu.tingbili.core.ui

import cn.debubu.tingbili.core.ui.component.snapStepValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** 步长滑轨的吸附规则：任何输入都只能落到 valueRange 内按 step 对齐的合法档位。 */
class StepSliderSnapTest {

    private val range = 5..60
    private val step = 5

    @Test
    fun `legal values are returned unchanged`() {
        assertEquals(5, snapStepValue(5, range, step))
        assertEquals(30, snapStepValue(30, range, step))
        assertEquals(60, snapStepValue(60, range, step))
    }

    @Test
    fun `midpoint rounds to nearest stop`() {
        assertEquals(5, snapStepValue(7, range, step))
        assertEquals(10, snapStepValue(8, range, step))
        assertEquals(10, snapStepValue(12, range, step))
        assertEquals(15, snapStepValue(13, range, step))
    }

    @Test
    fun `out of range clamps to bounds`() {
        assertEquals(5, snapStepValue(-40, range, step))
        assertEquals(5, snapStepValue(0, range, step))
        assertEquals(60, snapStepValue(999, range, step))
    }

    @Test
    fun `range not divisible by step never exceeds upper bound`() {
        // 5..58 步长 5：最大合法档位是 55，不能吸到 58 之外或产生 60
        assertEquals(55, snapStepValue(58, 5..58, 5))
        assertEquals(55, snapStepValue(57, 5..58, 5))
    }

    @Test
    fun `single stop range stays at its only value`() {
        assertEquals(5, snapStepValue(30, 5..5, 5))
    }
}
