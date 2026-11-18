package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.ReplyDetailDto
import cn.debubu.tingbili.data.bilibili.dto.ReplyMainDto
import cn.debubu.tingbili.data.bilibili.dto.toCommentDetailPage
import cn.debubu.tingbili.data.bilibili.dto.toCommentPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评论 DTO 映射单测（无网络）。
 * JSON 形状取自 `/x/v2/reply/main` 真实返回，只裁掉界面不用的字段。
 */
class ReplyDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun pageOf(body: String) = json.decodeFromString<ReplyMainDto>(body).toCommentPage()

    @Test
    fun `top reply comes first and is deduped from replies`() {
        val page = pageOf(
            """
            {
              "code": 0,
              "message": "OK",
              "data": {
                "cursor": {
                  "is_end": false,
                  "all_count": 2406,
                  "pagination_reply": { "next_offset": "CAEiAggC" }
                },
                "top_replies": [
                  {
                    "rpid": 111,
                    "mid": 1,
                    "count": 12,
                    "like": 99,
                    "ctime": 1794901330,
                    "member": { "mid": "1", "uname": "置顶君", "avatar": "//i0.hdslb.com/bfs/face/a.jpg" },
                    "content": { "message": "置顶评论" },
                    "replies": []
                  }
                ],
                "replies": [
                  {
                    "rpid": 111,
                    "mid": 1,
                    "count": 12,
                    "like": 99,
                    "ctime": 1794901330,
                    "member": { "mid": "1", "uname": "置顶君", "avatar": "//i0.hdslb.com/bfs/face/a.jpg" },
                    "content": { "message": "置顶评论" }
                  },
                  {
                    "rpid": 222,
                    "mid": 2,
                    "count": 0,
                    "rcount": 7,
                    "like": 5,
                    "ctime": 1794901175,
                    "member": {
                      "mid": "2",
                      "uname": "普通用户",
                      "avatar": "http://i1.hdslb.com/bfs/face/b.jpg@suffix",
                      "level_info": { "current_level": 6 }
                    },
                    "content": { "message": "沙发" }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(2, page.comments.size)
        assertEquals(111L, page.comments[0].rpid)
        assertTrue(page.comments[0].isTop)
        assertFalse(page.comments[1].isTop)
        assertEquals(2406, page.total)
        assertEquals("CAEiAggC", page.nextOffset)
        assertFalse(page.isEnd)
    }

    @Test
    fun `member fields are normalized for ui`() {
        val page = pageOf(
            """
            {
              "code": 0,
              "data": {
                "cursor": { "is_end": true, "all_count": 1 },
                "replies": [
                  {
                    "rpid": 1,
                    "mid": 0,
                    "count": 0,
                    "rcount": 7,
                    "like": 0,
                    "ctime": 1794901175,
                    "member": {
                      "mid": "42",
                      "uname": "某人",
                      "avatar": "http://i1.hdslb.com/bfs/face/b.jpg@suffix",
                      "level_info": { "current_level": 5 }
                    },
                    "content": { "message": "内容" }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val comment = page.comments.single()
        // member.mid 是字符串，要转成数字才能和 upMid 比对
        assertEquals(42L, comment.mid)
        assertEquals("某人", comment.uname)
        // http 明文升级 https，并去掉 CDN 的 @ 规格后缀
        assertEquals("https://i1.hdslb.com/bfs/face/b.jpg", comment.avatar)
        assertEquals(5, comment.level)
        // count 为 0 时回落到 rcount
        assertEquals(7, comment.replyCount)
        // 没有 pagination_reply / is_end=true：到底
        assertTrue(page.isEnd)
    }

    @Test
    fun `nested replies and upper mid are parsed`() {
        val page = pageOf(
            """
            {
              "code": 0,
              "data": {
                "cursor": { "is_end": true, "all_count": 1 },
                "upper": { "mid": 8888 },
                "replies": [
                  {
                    "rpid": 1,
                    "count": 2,
                    "like": 3,
                    "ctime": 1794901175,
                    "member": { "mid": "42", "uname": "楼主", "avatar": "" },
                    "content": { "message": "主楼" },
                    "replies": [
                      {
                        "rpid": 2,
                        "count": 0,
                        "like": 1,
                        "ctime": 1794901180,
                        "member": { "mid": "43", "uname": "楼中楼", "avatar": "" },
                        "content": { "message": "回复主楼" }
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val root = page.comments.single()
        assertEquals(2, root.replyCount)
        assertEquals(1, root.replies.size)
        assertEquals("楼中楼", root.replies.single().uname)
        assertEquals(8888L, page.upMid)
    }

    @Test
    fun `api error keeps message`() {
        val dto = json.decodeFromString<ReplyMainDto>(
            """{"code": -404, "message": "啥都木有", "data": null}"""
        )
        assertEquals(-404, dto.code)
        val page = dto.toCommentPage()
        assertTrue(page.comments.isEmpty())
        assertTrue(page.isEnd)
    }

    /**
     * 详情接口映射（抄 PiliPlus ReplyReplyData：page + upper + root + replies）。
     * JSON 形状取自 `/x/v2/reply/reply` 真实返回，只裁掉界面不用的字段。
     */
    @Test
    fun `reply detail maps root and paged children`() {
        val dto = json.decodeFromString<ReplyDetailDto>(
            """
            {
              "code": 0,
              "message": "OK",
              "data": {
                "page": { "num": 1, "size": 20, "count": 25 },
                "upper": { "mid": 8888 },
                "root": {
                  "rpid": 100,
                  "count": 25,
                  "like": 10,
                  "ctime": 1794901175,
                  "member": { "mid": "42", "uname": "楼主", "avatar": "" },
                  "content": { "message": "主楼" }
                },
                "replies": [
                  {
                    "rpid": 101,
                    "root": 100,
                    "parent": 100,
                    "dialog": 101,
                    "like": 2,
                    "ctime": 1794901180,
                    "member": { "mid": "43", "uname": "路人甲", "avatar": "" },
                    "content": { "message": "回复主楼" }
                  },
                  {
                    "rpid": 102,
                    "root": 100,
                    "parent": 101,
                    "dialog": 101,
                    "like": 0,
                    "ctime": 1794901190,
                    "member": { "mid": "44", "uname": "路人乙", "avatar": "" },
                    "content": { "message": "回复 @路人甲 ：说得对" }
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val page = dto.toCommentDetailPage()!!

        assertEquals(100L, page.root.rpid)
        assertEquals("楼主", page.root.uname)
        assertEquals(25, page.total)
        assertEquals(1, page.pageNum)
        assertFalse(page.isEnd)
        assertEquals(8888L, page.upMid)

        assertEquals(2, page.replies.size)
        // 直接回楼主：root == parent，预览用 ": " 分隔
        assertEquals(page.replies[0].root, page.replies[0].parent)
        // 楼中楼互回：root != parent，预览用 " " 分隔
        assertTrue(page.replies[1].root != page.replies[1].parent)
    }

    @Test
    fun `reply detail without root means deleted`() {
        val dto = json.decodeFromString<ReplyDetailDto>(
            """{"code": 0, "message": "OK", "data": {"page": {"num": 1, "size": 20, "count": 0}}}"""
        )
        assertTrue(dto.toCommentDetailPage() == null)
    }

    @Test
    fun `reply detail last page is end`() {
        val dto = json.decodeFromString<ReplyDetailDto>(
            """
            {
              "code": 0,
              "data": {
                "page": { "num": 2, "size": 20, "count": 25 },
                "root": {
                  "rpid": 100,
                  "count": 25,
                  "member": { "mid": "42", "uname": "楼主", "avatar": "" },
                  "content": { "message": "主楼" }
                },
                "replies": [
                  {
                    "rpid": 125,
                    "root": 100,
                    "parent": 100,
                    "member": { "mid": "45", "uname": "路人丙", "avatar": "" },
                    "content": { "message": "最后一页" }
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val page = dto.toCommentDetailPage()!!
        assertTrue(page.isEnd)
    }
}
