package cn.debubu.tingbili.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverDisplayModeTest {
    private val track = Track(
        bvid = "BV1",
        cid = 2,
        title = "P2",
        author = "up",
        cover = "https://cover",
        firstFrame = "https://first-frame",
        durationMs = 1000,
        subtitleUrl = null,
        pageCount = 2,
    )

    @Test
    fun `bv cover mode keeps original cover`() {
        assertEquals("https://cover", track.playbackCover(CoverDisplayMode.BV_COVER, false))
    }

    @Test
    fun `first frame mode uses part preview`() {
        assertEquals("https://first-frame", track.playbackCover(CoverDisplayMode.FIRST_FRAME, false))
    }

    @Test
    fun `single part exception keeps original cover`() {
        val single = track.copy(pageCount = 1)
        assertEquals("https://cover", single.playbackCover(CoverDisplayMode.FIRST_FRAME, true))
        assertEquals("https://first-frame", single.playbackCover(CoverDisplayMode.FIRST_FRAME, false))
    }

    @Test
    fun `missing first frame falls back to original cover`() {
        assertEquals("https://cover", track.copy(firstFrame = null).playbackCover(CoverDisplayMode.FIRST_FRAME, false))
        assertEquals("https://cover", track.copy(firstFrame = " ").playbackCover(CoverDisplayMode.FIRST_FRAME, false))
    }

    @Test
    fun `unknown persisted mode falls back to bv cover`() {
        assertEquals(CoverDisplayMode.BV_COVER, CoverDisplayMode.fromKey("unknown"))
        assertEquals(CoverDisplayMode.FIRST_FRAME, CoverDisplayMode.fromKey(CoverDisplayMode.FIRST_FRAME.name))
    }
}
