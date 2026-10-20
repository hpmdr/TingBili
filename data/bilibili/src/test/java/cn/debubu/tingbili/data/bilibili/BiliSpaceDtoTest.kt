package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto
import cn.debubu.tingbili.data.bilibili.dto.FavDetailDto
import cn.debubu.tingbili.data.bilibili.dto.FavFolderDto
import cn.debubu.tingbili.data.bilibili.dto.ToviewDto
import cn.debubu.tingbili.data.bilibili.dto.extractBvid
import cn.debubu.tingbili.data.bilibili.dto.toFolders
import cn.debubu.tingbili.data.bilibili.dto.toSpaceVideos
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliSpaceDtoTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Test
    fun `fav folders parse`() {
        val dto = json.decodeFromString<FavFolderDto>("""{"code":0,"data":{"count":1,"list":[{"id":123,"title":"default","cover":"http://cover","media_count":5}],"has_more":false}}""")
        val (folders, hasMore) = dto.toFolders()
        assertEquals(1, folders.size)
        assertEquals(123L, folders[0].id)
        assertEquals(5, folders[0].count)
        assertFalse(hasMore)
        assertTrue(folders[0].cover.startsWith("https://"))
    }

    @Test
    fun `fav medias playable only for ugc video`() {
        val dto = json.decodeFromString<FavDetailDto>("""{"code":0,"data":{"info":{"id":1,"title":"f"},"medias":[{"id":11,"type":2,"title":"v1","cover":"","bvid":"BV1xx","duration":60},{"id":12,"type":2,"title":"pgc","cover":"","bvid":"","duration":60,"ogv":{"title":"ot"}}],"has_more":true}}""")
        val (videos, hasMore) = dto.toSpaceVideos()
        assertEquals(2, videos.size)
        assertTrue(videos[0].playable)
        assertFalse(videos[1].playable)
        assertTrue(hasMore)
    }

    @Test
    fun `toview parse`() {
        val dto = json.decodeFromString<ToviewDto>("""{"code":0,"data":{"count":2,"list":[{"aid":1,"bvid":"BV1ab","title":"t","pic":"http://p","duration":100,"owner":{"name":"up"}}]}}""")
        val videos = dto.toSpaceVideos()
        assertEquals(1, videos.size)
        assertEquals("BV1ab", videos[0].bvid)
        assertEquals("up", videos[0].upper)
    }

    @Test
    fun `history extracts bvid and marks pgc unplayable`() {
        assertEquals("BV1AbcXyZ12", extractBvid("bilibili://video/BV1AbcXyZ12"))
        val dto = json.decodeFromString<BiliHistoryDto>("""{"code":0,"data":{"list":[{"title":"v","cover":"","uri":"bilibili://video/BV1xx","author_name":"up","view_at":1,"history":{"oid":9,"business":"archive"}},{"title":"live","cover":"","uri":"bilibili://live/1","history":{"oid":2,"business":"live"}}]}}""")
        val videos = dto.toSpaceVideos()
        assertEquals(2, videos.size)
        assertTrue(videos[0].playable)
        assertFalse(videos[1].playable)
    }
}
