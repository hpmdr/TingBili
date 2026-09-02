package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.data.bilibili.dto.SubtitleBodyItem
import cn.debubu.tingbili.data.bilibili.dto.SubtitleData
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleInfo
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParserTest {

    @Test fun `subtitle parser picks投稿 over AI via direct body`() {
        // Direct body (投稿) should be returned, AI fallback would be empty in our stub
        val dto = SubtitleDto(
            code = 0,
            data = SubtitleData(
                body = listOf(
                    SubtitleBodyItem(from = 0.0, to = 2.0, content = "投稿第一行"),
                    SubtitleBodyItem(from = 2.0, to = 4.0, content = "投稿第二行")
                )
            )
        )
        val lines = SubtitleParser.parse(dto)
        assertEquals("投稿第一行", lines[0].text)
        assertEquals(0L, lines[0].timeMs)
    }

    @Test fun `subtitle parser manual over AI helper`() {
        val manual = listOf(LyricLine(0L, "投稿第一行"), LyricLine(2000L, "投稿第二"))
        val ai = listOf(LyricLine(0L, "AI第一行"))
        val lines = SubtitleParser.parse(manual, ai)
        assertEquals("投稿第一行", lines[0].text)
        // when manual empty, fallback to AI
        val fallback = SubtitleParser.parse(emptyList(), ai)
        assertEquals("AI第一行", fallback[0].text)
    }

    @Test fun `lyric index follows position`() {
        val state = LyricState(listOf(LyricLine(0, "a"), LyricLine(5000, "b"), LyricLine(10000, "c")))
        assertEquals(0, state.indexFor(0))
        assertEquals(0, state.indexFor(4999))
        assertEquals(1, state.indexFor(6000))
        assertEquals(2, state.indexFor(15000))
        assertEquals(-1, LyricState(emptyList()).indexFor(6000))
    }
}
