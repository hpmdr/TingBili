package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.Owner
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.ViewPage
import cn.debubu.tingbili.data.bilibili.dto.toFavoriteSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteSnapshotTest {

    @Test
    fun `view data maps to favorite snapshot with pages`() {
        val data = ViewData(
            bvid = "BV1",
            title = "书",
            pic = "//i0.hdslb.com/cover.jpg",
            duration = 120,
            owner = Owner(name = "up", mid = 42),
            pages = listOf(
                ViewPage(cid = 1, page = 1, part = "P1", duration = 60),
                ViewPage(cid = 2, page = 2, part = "P2", duration = 60),
            ),
        )

        val snapshot = data.toFavoriteSnapshot()

        assertEquals("BV1", snapshot.bvid)
        assertEquals("书", snapshot.title)
        assertEquals("up", snapshot.author)
        assertEquals(42L, snapshot.authorMid)
        assertEquals(120_000L, snapshot.durationMs)
        assertEquals(2, snapshot.pages.size)
        assertEquals(1L, snapshot.pages[0].cid)
        assertEquals(1, snapshot.pages[0].pageIndex)
        assertEquals("P1", snapshot.pages[0].title)
        assertEquals(2, snapshot.pages[1].pageIndex)
        assertEquals(60_000L, snapshot.pages[1].durationMs)
    }
}
