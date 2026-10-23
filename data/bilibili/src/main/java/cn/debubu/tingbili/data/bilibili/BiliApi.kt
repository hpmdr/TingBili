package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto
import cn.debubu.tingbili.data.bilibili.dto.FavDetailDto
import cn.debubu.tingbili.data.bilibili.dto.FavFolderDto
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.ToviewDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import retrofit2.http.GET
import retrofit2.http.Query

interface BiliApi {

    @GET("/x/web-interface/search/type")
    suspend fun search(
        @Query("keyword") keyword: String,
        @Query("search_type") searchType: String = "video",
        @Query("page") page: Int = 1
    ): SearchDto

    @GET("/x/web-interface/view")
    suspend fun view(
        @Query("bvid") bvid: String
    ): ViewDto

    // 相关推荐（对标 PiliPlus relatedVideoList）：只传 bvid，无需 WBI/登录，一次约 10~20 条
    @GET("/x/web-interface/archive/related")
    suspend fun related(
        @Query("bvid") bvid: String
    ): RelatedDto

    @GET("/x/player/playurl")
    suspend fun playUrl(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long,
        @Query("fnval") fnval: Int = 16
    ): PlayUrlDto

    // B站账号体系(我的页二级页,只读):收藏夹/夹内/稍后看/历史,对标 PiliPlus
    @GET("/x/v3/fav/folder/created/list")
    suspend fun favFolders(
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
        @Query("up_mid") upMid: Long = 0L,
    ): FavFolderDto

    @GET("/x/v3/fav/resource/list")
    suspend fun favMedias(
        @Query("media_id") mediaId: Long,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
        @Query("keyword") keyword: String = "",
        @Query("order") order: String = "mtime",
        @Query("type") type: Int = 0,
        @Query("tid") tid: Int = 0,
        @Query("platform") platform: String = "web",
    ): FavDetailDto

    @GET("/x/v2/history/toview/web")
    suspend fun toview(
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 20,
    ): ToviewDto

    @GET("/x/web-interface/history/cursor")
    suspend fun historyCursor(
        @Query("type") type: String = "all",
        @Query("ps") ps: Int = 20,
        @Query("max") max: Long = 0L,
        @Query("view_at") viewAt: Long = 0L,
    ): BiliHistoryDto

    @GET("/x/player/wbi/v2")
    suspend fun subtitle(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long
    ): SubtitleDto
}
