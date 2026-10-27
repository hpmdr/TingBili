package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.data.bilibili.dto.RelationStatDto
import cn.debubu.tingbili.data.bilibili.dto.SpaceInfoDto
import cn.debubu.tingbili.data.bilibili.dto.SpaceVideosDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * UP 主空间接口（对标 PiliPlus）：
 * - acc/info：头像 / 昵称 / 签名
 * - relation/stat：粉丝数（acc/info 不含）
 * - arc/search：投稿列表，order = pubdate（最新发布）/ click（最多播放）/ stow（最多收藏）
 *
 * 前两个走 /wbi/ 路径，由 [WbiInterceptor] 自动补 w_rid/wts；order_avoided=true 是
 * PiliPlus 同款风控规避参数，别漏。
 */
interface SpaceApi {

    @GET("/x/space/wbi/acc/info")
    suspend fun info(
        @Query("mid") mid: Long,
        @Query("platform") platform: String = "web_page",
        @Query("web_location") webLocation: String = "1550101",
    ): SpaceInfoDto

    @GET("/x/relation/stat")
    suspend fun stat(@Query("vmid") mid: Long): RelationStatDto

    @GET("/x/space/wbi/arc/search")
    suspend fun videos(
        @Query("mid") mid: Long,
        @Query("pn") pn: Int = 1,
        @Query("ps") ps: Int = 30,
        @Query("order") order: String = "pubdate",
        // 空串 = 不过滤；非空时只返回该 UP 内标题命中的投稿，可与 order 叠加
        @Query("keyword") keyword: String = "",
        @Query("platform") platform: String = "web",
        @Query("web_location") webLocation: String = "1550101",
        @Query("order_avoided") orderAvoided: Boolean = true,
    ): SpaceVideosDto
}
