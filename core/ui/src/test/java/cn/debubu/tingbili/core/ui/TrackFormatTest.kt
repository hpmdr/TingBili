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

    @Test
    fun `track page label uses page index without page count`() {
        // 收藏夹的 Track 没有真实页数（默认 1），但 P 序号仍应显示
        assertEquals("P19", trackPageLabel("97-108", "【一口气看完】…", 19))
        assertEquals("P1", trackPageLabel("1-20", "无限流…", 1))
        assertEquals(null, trackPageLabel("1-20", "1-20", 1))
        assertEquals(null, trackPageLabel("单曲", null, null))
    }

    @Test
    fun `track meta label mirrors favorites row subtitle`() {
        assertEquals(
            "P17 · 无限流悬疑多人有声《请活到天明》连载最新",
            trackMetaLabel("295-308（1023）", "无限流悬疑多人有声《请活到天明》连载最新", 17),
        )
        // 主标题已经是 BV 标题：只留分 P
        assertEquals("P19", trackMetaLabel("97-108", "【一口气看完】…", 19, showVideoTitle = false))
        // 单 P / 同名单 P：整行没有小字
        assertEquals(null, trackMetaLabel("一首单曲", "一首单曲", 1))
        assertEquals(null, trackMetaLabel("一首单曲", null, 1))
    }
}
