package cn.debubu.tingbili.core.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 切歌/复位语义：decoderName 复用时不清（e210946），offload 粘性标记切歌即清。 */
class AudioPipelineMonitorTest {

    @After
    fun tearDown() {
        AudioPipelineMonitor.resetTrackState()
    }

    @Test
    fun `new track clears offload flags but keeps decoder name`() {
        AudioPipelineMonitor.onDecoderInitialized("OMX.qcom.audio.decoder.aac")
        AudioPipelineMonitor.onOffloadedPlaybackChanged(true)
        AudioPipelineMonitor.onOffloadSleepingChanged(true)

        AudioPipelineMonitor.onNewTrack()

        // 解码器复用时名字不变，必须保留，否则信息页永远补不回来
        assertEquals("OMX.qcom.audio.decoder.aac", AudioPipelineMonitor.decoderName.value)
        assertEquals(false, AudioPipelineMonitor.offloadEverActive.value)
        assertEquals(false, AudioPipelineMonitor.offloadedPlayback.value)
        assertEquals(false, AudioPipelineMonitor.offloadSleeping.value)
    }

    @Test
    fun `reset clears decoder and offload flags`() {
        AudioPipelineMonitor.onDecoderInitialized("c2.android.aac.decoder")
        AudioPipelineMonitor.onOffloadSleepingChanged(true)

        AudioPipelineMonitor.resetTrackState()

        assertNull(AudioPipelineMonitor.decoderName.value)
        assertEquals(false, AudioPipelineMonitor.offloadEverActive.value)
        assertEquals(false, AudioPipelineMonitor.offloadedPlayback.value)
        assertEquals(false, AudioPipelineMonitor.offloadSleeping.value)
    }
}
