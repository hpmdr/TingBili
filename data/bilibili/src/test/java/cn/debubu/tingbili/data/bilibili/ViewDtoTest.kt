package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.Dimension
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.ViewPage
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewDtoTest {
    @Test
    fun `view pages preserve their own first frame and original cover`() {
        val video = ViewData(
            bvid = "BV1",
            title = "合集",
            pic = "http://i0.hdslb.com/cover.jpg",
            pages = listOf(
                ViewPage(
                    cid = 1,
                    page = 1,
                    part = "第一首",
                    duration = 60,
                    dimension = Dimension(1920, 1080),
                    firstFrame = "http://i1.hdslb.com/first1.jpg",
                ),
                ViewPage(cid = 2, page = 2, part = "第二首", duration = 60),
            ),
        )

        val tracks = video.toTracks()

        assertEquals("https://i0.hdslb.com/cover.jpg", tracks[0].cover)
        assertEquals("https://i0.hdslb.com/cover.jpg", tracks[1].cover)
        assertEquals("https://i1.hdslb.com/first1.jpg", tracks[0].firstFrame)
        assertNull(tracks[1].firstFrame)
    }

    @Test
    fun `first frame is decoded from api field name`() {
        val page = Json.decodeFromString(
            ViewPage.serializer(),
            """{"cid":7,"page":1,"part":"P1","duration":10,"first_frame":"//i0.hdslb.com/first.jpg"}""",
        )

        assertEquals("//i0.hdslb.com/first.jpg", page.firstFrame)
    }
}
