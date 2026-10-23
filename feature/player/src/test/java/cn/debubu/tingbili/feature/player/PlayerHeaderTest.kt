package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.core.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerHeaderTest {

    private fun track(
        title: String,
        videoTitle: String? = null,
        pageIndex: Int? = null,
        pageCount: Int = 42
    ) = Track(
        bvid = "BV1",
        cid = 1L,
        title = title,
        author = "散人听书",
        cover = "",
        durationMs = 1000L,
        subtitleUrl = null,
        pageCount = pageCount,
        videoTitle = videoTitle,
        pageIndex = pageIndex
    )

    @Test
    fun `header prefers video title over part title`() {
        val t = track(title = "M　1-30", videoTitle = "民调局异闻录")
        assertEquals("民调局异闻录", playerHeaderTitle(t))
    }

    @Test
    fun `header falls back to part title when video title missing or blank`() {
        assertEquals("M　1-30", playerHeaderTitle(track(title = "M　1-30")))
        assertEquals("M　1-30", playerHeaderTitle(track(title = "M　1-30", videoTitle = "   ")))
    }

    @Test
    fun `chapter prefixes current episode when index known`() {
        assertEquals("本集 P1 · M　1-30", playerChapterLabel(track(title = "M　1-30", pageIndex = 1)))
    }

    @Test
    fun `chapter prefixes even when total count is default`() {
        // 整单播放时队列 track 的 pageCount 常是默认值，只认序号
        assertEquals("本集 P3 · M　1-30", playerChapterLabel(track(title = "M　1-30", pageIndex = 3, pageCount = 1)))
    }

    @Test
    fun `chapter has no prefix for single or unknown index`() {
        assertEquals("M　1-30", playerChapterLabel(track(title = "M　1-30")))
        assertEquals("未播放", playerChapterLabel(null))
        assertEquals("未播放", playerChapterLabel(track(title = "")))
    }

    @Test
    fun `header shows placeholder without track`() {
        assertEquals("未播放", playerHeaderTitle(null))
        assertEquals("未播放", playerHeaderTitle(track(title = "")))
    }

}
