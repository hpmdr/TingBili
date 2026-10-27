package cn.debubu.tingbili.feature.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRateLimitedException
import cn.debubu.tingbili.data.bilibili.BiliRepository
import kotlinx.coroutines.delay

/**
 * 首页唯一的列表数据源：一个关键词的搜索结果分页。
 * 推荐只是“进入页面时用默认/上次搜索词自动执行一次同样的搜索”，与手动搜索完全等价，
 * 所以全程只有这一条流、一个列表，不区分“推荐列表”和“搜索结果”。
 *
 * @param onFirstPage 首屏加载成功回调（SWR 快照用），调用方负责落盘，异常内部吞掉不影响分页。
 */
class SearchPagingSource(
    private val repo: BiliRepository,
    private val keyword: String,
    private val onFirstPage: ((tracks: List<Track>, total: Int) -> Unit)? = null
) : PagingSource<Int, Track>() {

    // 同一个 Pager 存活期间见过的 bvid：B 站搜索翻页常重复返回同一 BV，跨页去重。
    // Pager 重建（换词/刷新）即清空。
    private val seenBvids = mutableSetOf<String>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val page = params.key ?: 1
        return try {
            if (keyword.isBlank()) {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
            val (raw, total) = repo.searchPageWithTotal(keyword, page)
            val tracks = raw.filter { seenBvids.add(it.bvid) }
            if (page == 1) {
                try {
                    onFirstPage?.invoke(tracks, total)
                } catch (_: Exception) {
                    // 快照回调永不打断分页
                }
            }
            // 全是重复时仍保留 nextKey，Paging 会继续往后翻找新内容，而不是把重复项再铺一遍
            val nextKey = if (raw.isEmpty()) null else page + 1
            val prevKey = if (page == 1) null else page - 1
            LoadResult.Page(data = tracks, prevKey = prevKey, nextKey = nextKey)
        } catch (e: BiliRateLimitedException) {
            // 412 风控：先退避再交 Error，掐断连击风暴
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
        const val RATE_LIMIT_BACKOFF_MS = 2000L
    }
}
