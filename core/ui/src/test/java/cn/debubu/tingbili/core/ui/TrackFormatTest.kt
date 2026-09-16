package cn.debubu.tingbili.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackFormatTest {

    @Test
    fun `duration formats minutes and hours`() {
        assertEquals("0:00", formatDurationMs(0L))
        assertEquals("0:41", formatDurationMs(41_000L))
        assertEquals("4:50:51", formatDurationMs(17_451_000L))
    }

    @Test
    fun `progress label prefixes part number`() {
        assertEquals("P1 · 0:41", playedProgressLabel(1, 41_000L))
        assertEquals("P1 · 0:41 / 4:50:51", playedProgressLabel(1, 41_000L, 17_451_000L))
    }

    @Test
    fun `chapter label prefixes part number for multi page video`() {
        assertEquals("P1 · M　1-30", chapterLabel("M　1-30", pageIndex = 1, pageCount = 42))
        assertEquals("P7 · M　181-210", chapterLabel("M　181-210", pageIndex = 7, pageCount = 42))
    }

    @Test
    fun `chapter label stays plain for single page or unknown index`() {
        assertEquals("一首单曲", chapterLabel("一首单曲", pageIndex = 1, pageCount = 1))
        assertEquals("M　1-30", chapterLabel("M　1-30", pageIndex = null, pageCount = 42))
        assertEquals("M　1-30", chapterLabel("M　1-30", pageIndex = 0, pageCount = 42))
        assertEquals("未播放", chapterLabel(null, pageIndex = 1, pageCount = 42))
        assertEquals("未播放", chapterLabel("   ", pageIndex = 1, pageCount = 42))
    }

    @Test
    fun `progress label omits unknown page and unknown duration`() {
        assertEquals("0:41", playedProgressLabel(null, 41_000L, 17_451_000L).substringBefore(" / "))
        assertEquals("P7 · 0:41", playedProgressLabel(7, 41_000L, 0L))
        assertEquals("0:41", playedProgressLabel(0, 41_000L, null))
    }
}
