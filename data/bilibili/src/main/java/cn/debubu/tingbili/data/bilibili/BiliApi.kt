package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import retrofit2.http.GET
import retrofit2.http.Query

interface BiliApi {

    @GET("/x/web-interface/search/type")
    suspend fun search(
        @Query("keyword") keyword: String,
        @Query("search_type") searchType: String = "video"
    ): SearchDto

    @GET("/x/web-interface/view")
    suspend fun view(
        @Query("bvid") bvid: String
    ): ViewDto

    @GET("/x/player/playurl")
    suspend fun playUrl(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long,
        @Query("fnval") fnval: Int = 16
    ): PlayUrlDto

    @GET("/x/player/wbi/v2")
    suspend fun subtitle(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long
    ): SubtitleDto
}
