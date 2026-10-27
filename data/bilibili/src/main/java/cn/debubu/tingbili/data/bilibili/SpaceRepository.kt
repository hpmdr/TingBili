package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.data.bilibili.dto.UpSpaceInfo
import cn.debubu.tingbili.data.bilibili.dto.UpVideo
import cn.debubu.tingbili.data.bilibili.dto.toSpaceInfo
import cn.debubu.tingbili.data.bilibili.dto.toUpVideos
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UP 主空间数据源，独立于 [BiliRepository]：
 * 空间接口只在 UP 主页用，没必要塞进主仓库，也避免影响既有测试的 Fake 实现。
 */
@Singleton
class SpaceRepository @Inject constructor(
    private val api: SpaceApi,
) {

    /** UP 主头部信息；粉丝数单独请求，失败时降级为 0，不拖垮整页 */
    suspend fun info(mid: Long): Result<UpSpaceInfo> = try {
        val dto = api.info(mid)
        val base = dto.toSpaceInfo()
        if (dto.code != 0 || base == null) {
            Result.Error(dto.message.ifBlank { "加载 UP 主信息失败（code ${dto.code}）" })
        } else {
            val stat = runCatching { api.stat(mid).data }.getOrNull()
            Result.Success(base.copy(fans = stat?.follower ?: 0L, following = stat?.following ?: 0L))
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "加载 UP 主信息失败", e)
    }

    /**
     * 投稿分页（page 从 1 开始）。
     * @param order pubdate（最新发布）/ click（最多播放）/ stow（最多收藏）
     * @return (视频列表, 投稿总数)
     */
    suspend fun videos(mid: Long, page: Int, order: String, keyword: String = ""): Result<Pair<List<UpVideo>, Int>> = try {
        val dto = api.videos(mid = mid, pn = page, order = order, keyword = keyword)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "加载投稿失败（code ${dto.code}）" })
        } else {
            Result.Success(dto.toUpVideos())
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "加载投稿失败", e)
    }
}
