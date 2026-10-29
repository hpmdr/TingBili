package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 搜索接口 → Track 的字段映射（首页卡片的分 P 角标与 UP 主页跳转依赖这两个字段）。 */
class SearchDtoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `maps page count and up mid`() {
        val raw = """
            {"code":0,"data":{"result":[
              {"type":"video","bvid":"BV1xx411c7mD","title":"合集","author":"某UP","pic":"","duration":"1:00:00","videos":83,"mid":123456}
            ],"numResults":1,"numPages":1}}
        """.trimIndent()
        val track = json.decodeFromString<SearchDto>(raw).toTracks().single()
        assertEquals(83, track.pageCount)
        assertEquals(123456L, track.upMid)
    }

    @Test
    fun `missing mid leaves up mid null`() {
        val raw = """
            {"code":0,"data":{"result":[
              {"type":"video","bvid":"BV1xx411c7mD","title":"单P","author":"某UP","pic":"","duration":"3:00","videos":0}
            ],"numResults":1,"numPages":1}}
        """.trimIndent()
        val track = json.decodeFromString<SearchDto>(raw).toTracks().single()
        // videos=0 视为单 P；没有 mid 就不给可点的作者行
        assertEquals(1, track.pageCount)
        assertNull(track.upMid)
    }
}
