package cn.debubu.tingbili.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScrollTopBusTest {
    @Test
    fun `tick starts at zero and increments`() {
        val bus = HomeScrollTopBus()
        assertEquals(0, bus.tick.value)
        bus.requestScrollTop()
        bus.requestScrollTop()
        assertEquals(2, bus.tick.value)
    }
}
