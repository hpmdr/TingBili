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
import retrofit2.http.Header
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

    // 音质直通：qn 传音频偏好 id（如 30232/30280/30251），服务端按登录/会员态鉴权返回可用档；
    // 不传则服务端给默认档，客户端再按偏好 rank 二次精选（见 selectAudio），双保险不断播。
    // 注意：**不要**改用 /x/player/wbi/playurl——2026-11 实测同一 cookie 下 wbi 路径
    // 返回 code 0 但 dash.audio 为空（静默降级，杜比/Hi-Res/普通档全没了），web 路径正常。
    @GET("/x/player/playurl")
    suspend fun playUrl(
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long,
        @Query("fnval") fnval: Int = PLAYURL_FNVAL,
        @Query("qn") qn: Int? = null,
        /** 游客标记：拦截器据此跳过登录 Cookie（风控兜底） */
        @Header(WbiInterceptor.GUEST_HEADER) guest: Boolean = false,
    ): PlayUrlDto

    companion object {
        /**
         * DASH(16) | 杜比音频(256)。
         * 不置 256 时服务端根本不返回 `dash.dolby` / `dash.flac`，大会员也拿不到 30250/30251
         *（2026-11 用户反馈"会员也放不了杜比/Hi-Res"的根因之一；另一半是 DTO 没解析这两个节点）。
         */
        const val PLAYURL_FNVAL = 16 or 256
    }

    // B站账号体系(我的页二级页,只读):收藏夹/夹内/稍后看/历史,对标 PiliPlus
    // list-all 一次拉全，不再分页
    @GET("/x/v3/fav/folder/created/list-all")
    suspend fun favFoldersAll(
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
