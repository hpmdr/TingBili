package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import cn.debubu.tingbili.data.bilibili.dto.normalizeBiliImageUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlTest {

    @Test
    fun `normalizes protocol and strips existing suffix`() {
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/a.jpg",
            "http://i2.hdslb.com/bfs/archive/a.jpg@480w_270h_1c.webp".normalizeBiliImageUrl(),
        )
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/a.jpg",
            "//i2.hdslb.com/bfs/archive/a.jpg".normalizeBiliImageUrl(),
        )
    }

    @Test
    fun `builds sized webp and avif urls`() {
        val base = "https://i2.hdslb.com/bfs/archive/a.jpg"
        assertEquals(
            "$base@480w_270h_1c.avif",
            base.biliImage(BiliImageVariant.ListThumb, "avif"),
        )
        assertEquals(
            "$base@480w_480h_1c.webp",
            base.biliImage(BiliImageVariant.SquareCover, "webp"),
        )
    }
}
