package cn.debubu.tingbili.feature.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRateLimitedException
import cn.debubu.tingbili.data.bilibili.BiliRepository
import kotlinx.coroutines.delay

/**
 * 首页发现流：策展关键词分页轮换（第 1 页搜词 1、第 2 页搜词 2……循环），
 * 关键词列表来自设置（DataStore），增减后自动刷新。
 *
 * 同一关键词的第几轮决定接口页码（轮内从第 1 页起取）：第 5 页=词 1 第 2 页，
 * 而不是接口第 5 页，否则每个词的前几页永远被跳过。
 *
 * B 站不同关键词搜出的 BV 高度重叠（有声小说/有声书基本同池），
 * 这里按 bvid 跨页去重，否则上滑全是重复内容。
 * @param onFirstPage 首页加载成功后的回调（SWR 快照用），调用方负责落盘，异常内部吞掉不影响分页。
 */
class HomePagingSource(
    private val repo: BiliRepository,
    private val keywords: List<String> = emptyList(),
    private val onFirstPage: ((tracks: List<Track>, orderedKeywords: List<String>) -> Unit)? = null
) : PagingSource<Int, Track>() {

    // 同一个 Pager 存活期间见过的 bvid：跨关键词/跨页去重用。
    // Pager 重建（下拉刷新/关键词变更）即清空，允许新一轮推荐重新出现。
    private val seenBvids = mutableSetOf<String>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val page = params.key ?: 1
        return try {
            val kws = keywords.ifEmpty { DEFAULT_RECOMMEND_KEYWORDS }
            val keyword = kws[(page - 1) % kws.size]
            val apiPage = (page - 1) / kws.size + 1
            val raw = repo.searchPage(keyword, apiPage)
            // 跨页去重：已见过的 BV 直接丢掉，nextKey 照常推进（避免卡死）。
            // 全是重复时返回空页 + 保留 nextKey，Paging 会继续往后翻找新内容，
            // 而不是把重复项再铺一遍给用户看。
            val tracks = raw.filter { seenBvids.add(it.bvid) }
            val nextKey = if (raw.isEmpty()) null else page + 1
            val prevKey = if (page == 1) null else page - 1
            if (page == 1 && tracks.isNotEmpty()) {
                try {
                    onFirstPage?.invoke(tracks, kws)
                } catch (_: Exception) {
                    // 快照回调永不打断分页
                }
            }
            LoadResult.Page(data = tracks, prevKey = prevKey, nextKey = nextKey)
        } catch (e: BiliRateLimitedException) {
            // 412 风控：在 load 内先退避 2s 再交 Error，
            // 否则快速上滑 + 连点重试会形成请求风暴，412 一直不消。
            try {
                delay(RATE_LIMIT_BACKOFF_MS)
            } catch (_: Exception) {
            }
            LoadResult.Error(
                BiliRateLimitedException("请求太频繁，B站限流了（412），稍后再试", e)
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Track>): Int? {
        return state.anchorPosition?.let { pos ->
            val page = state.closestPageToPosition(pos)
            page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
        }
    }

    companion object {
        /** 默认策展关键词（与 PreferencesRepository.DEFAULT_HOME_KEYWORDS 一致，用于兜底） */
        val DEFAULT_RECOMMEND_KEYWORDS = listOf("有声小说", "有声书", "悬疑小说", "多人有声")
        /** 412 后退避：load 内先睡一下再返回 Error，掐断连击风暴。 */
        const val RATE_LIMIT_BACKOFF_MS = 2000L
    }
}
