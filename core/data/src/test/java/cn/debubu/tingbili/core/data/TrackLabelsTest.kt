package cn.debubu.tingbili.core.data

import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.model.episodeLabel
import cn.debubu.tingbili.core.data.model.episodePageLabel
import cn.debubu.tingbili.core.data.model.queueLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 分集标题口径：播放页、迷你、列表、通知栏共用，单 P 不显示 P1。 */
class TrackLabelsTest {

    private fun track(
        title: String,
        videoTitle: String? = null,
        pageIndex: Int? = null,
    ) = Track(
        bvid = "BV1",
        cid = 1,
        title = title,
        author = "a",
        cover = "",
        durationMs = 1000,
        subtitleUrl = null,
        videoTitle = videoTitle,
        pageIndex = pageIndex,
    )

    @Test
    fun `multi part label keeps page number`() {
        val t = track("295-308", videoTitle = "无限流悬疑多人有声", pageIndex = 17)
        assertEquals("P17", episodePageLabel(t.title, t.videoTitle, t.pageIndex))
        assertEquals("P17 · 295-308", t.episodeLabel())
    }

    @Test
    fun `multi part first episode still shows P1`() {
        val t = track("1-20", videoTitle = "无限流悬疑多人有声", pageIndex = 1)
        assertEquals("P1 · 1-20", t.episodeLabel())
    }

    @Test
    fun `single part hides P1`() {
        assertEquals("一首单曲", track("一首单曲", pageIndex = 1).episodeLabel())
        assertEquals(
            "一首单曲",
            track("一首单曲", videoTitle = "一首单曲", pageIndex = 1).episodeLabel(),
        )
        assertNull(episodePageLabel("一首单曲", "一首单曲", 1))
    }

    @Test
    fun `unknown page index falls back to title`() {
        assertEquals("一首单曲", track("一首单曲").episodeLabel())
    }

    @Test
    fun `queue label uses playlist position not page number`() {
        // 混播时各条 P 序号各说各话，队列序号才是统一口径
        val t = track("295-308", videoTitle = "无限流悬疑多人有声", pageIndex = 17)
        assertEquals("03 · 295-308", t.queueLabel(2))
        assertEquals("01 · 一首单曲", track("一首单曲").queueLabel(0))
    }
}
