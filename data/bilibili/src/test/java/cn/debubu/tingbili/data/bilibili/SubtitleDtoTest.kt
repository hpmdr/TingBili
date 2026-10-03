package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.SubtitleFile
import cn.debubu.tingbili.data.bilibili.dto.SubtitleInfo
import cn.debubu.tingbili.data.bilibili.dto.SubtitleItem
import cn.debubu.tingbili.data.bilibili.dto.normalizeSubtitleUrl
import cn.debubu.tingbili.data.bilibili.dto.toLyricLines
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选轨规则单测（无网络）。
 * 对应 /x/player/wbi/v2 真实返回里 `data.subtitle.subtitles[]` 的形状。
 */
class SubtitleDtoTest {

    private fun dto(vararg items: SubtitleItem) =
        cn.debubu.tingbili.data.bilibili.dto.SubtitleDto(
            code = 0,
            data = cn.debubu.tingbili.data.bilibili.dto.SubtitleData(
                subtitle = SubtitleInfo(subtitles = items.toList())
            )
        )

    private fun track(lan: String, url: String, type: Int) =
        SubtitleItem(lan = lan, lanDoc = lan, subtitleUrl = url, type = type)

    @Test
    fun `protocol relative subtitle url gets https scheme`() {
        assertEquals(
            "https://aisubtitle.hdslb.com/bfs/subtitle/a.json",
            "//aisubtitle.hdslb.com/bfs/subtitle/a.json".normalizeSubtitleUrl()
        )
        assertEquals(
            "https://aisubtitle.hdslb.com/a.json",
            "http://aisubtitle.hdslb.com/a.json".normalizeSubtitleUrl()
        )
        // 已经是绝对地址的不动
        assertEquals(
            "https://x.dev/a.json",
            "https://x.dev/a.json".normalizeSubtitleUrl()
        )
    }

    @Test
    fun `chinese beats english and manual beats ai`() {
        val tracks = dto(
            track("en-US", "//x/en-manual.json", 0),
            track("ai-en", "//x/ai-en.json", 1),
            track("ai-zh", "//x/ai-zh.json", 1),
            track("zh-CN", "//x/zh-manual.json", 0),
        ).toTracks()

        assertEquals(listOf("zh-CN", "ai-zh", "en-US", "ai-en"), tracks.map { it.lan })
    }

    @Test
    fun `ai flag comes from type equals one`() {
        val tracks = dto(
            track("zh-CN", "//x/m.json", 0),
            track("ai-zh", "//x/a.json", 1),
        ).toTracks()

        assertEquals(false, tracks.first { it.lan == "zh-CN" }.isAi)
        assertEquals(true, tracks.first { it.lan == "ai-zh" }.isAi)
    }

    @Test
    fun `falls back to subtitle_url_v2 and drops url-less tracks`() {
        val tracks = dto(
            SubtitleItem(lan = "zh-CN", subtitleUrl = "", subtitleUrlV2 = "//x/v2.json"),
            SubtitleItem(lan = "en-US", subtitleUrl = "", subtitleUrlV2 = ""),
        ).toTracks()

        assertEquals(1, tracks.size)
        assertEquals("https://x/v2.json", tracks[0].url)
    }

    @Test
    fun `empty subtitle list yields no tracks`() {
        assertTrue(dto().toTracks().isEmpty())
    }

    @Test
    fun `body sorted by start time with blanks dropped`() {
        val lines = SubtitleFile(
            body = listOf(
                cn.debubu.tingbili.data.bilibili.dto.SubtitleBodyItem(from = 2.0, content = " 第二句 "),
                cn.debubu.tingbili.data.bilibili.dto.SubtitleBodyItem(from = 0.0, content = "第一句"),
                cn.debubu.tingbili.data.bilibili.dto.SubtitleBodyItem(from = 1.0, content = "  "),
            )
        ).toLyricLines()

        assertEquals(2, lines.size)
        assertEquals("第一句", lines[0].text)
        assertEquals(2000L, lines[1].timeMs)
        assertEquals("第二句", lines[1].text)
    }
}