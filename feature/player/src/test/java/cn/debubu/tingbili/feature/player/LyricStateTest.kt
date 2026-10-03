package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.data.bilibili.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 逐行歌词的当前行定位。字幕轨挑选（中文优先 / 人工优先于 AI）在
 * `data:bilibili` 的 `SubtitleDto.toTracks()`，那里有对应单测。
 */
class LyricStateTest {

    @Test
    fun `index follows position`() {
        val state = LyricState(listOf(LyricLine(0, "a"), LyricLine(5000, "b"), LyricLine(10000, "c")))
        assertEquals(0, state.indexFor(0))
        assertEquals(0, state.indexFor(4999))
        assertEquals(1, state.indexFor(6000))
        assertEquals(2, state.indexFor(15000))
    }

    @Test
    fun `no lines means no current index`() {
        assertEquals(-1, LyricState(emptyList()).indexFor(6000))
    }
}