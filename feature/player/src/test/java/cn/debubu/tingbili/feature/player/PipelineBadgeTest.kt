package cn.debubu.tingbili.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineBadgeTest {

    @Test
    fun `unknown decoder shows nothing`() {
        assertEquals(null, pipelineBadge(null, false))
    }

    @Test
    fun `software decoder labeled`() {
        assertEquals("软解 · c2.android.aac.decoder", pipelineBadge("c2.android.aac.decoder", false))
        assertEquals("软解 · OMX.google.aac.decoder", pipelineBadge("OMX.google.aac.decoder", false))
    }

    @Test
    fun `hardware decoder labeled`() {
        assertEquals("硬解 · c2.mtk.aac.decoder", pipelineBadge("c2.mtk.aac.decoder", false))
        assertEquals("硬解 · c2.qti.aac.decoder", pipelineBadge("c2.qti.aac.decoder", false))
    }

    @Test
    fun `offload wins over decoder name`() {
        assertEquals("DSP offload 生效中", pipelineBadge("c2.android.aac.decoder", true))
    }
}
