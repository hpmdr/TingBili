package cn.debubu.tingbili.feature.search

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRateLimitedException
import cn.debubu.tingbili.data.bilibili.BiliRepository
import kotlinx.coroutines.delay

class SearchPagingSource(
    private val repo: BiliRepository,
    private val keyword: String,
    private val onTotalLoaded: (Int) -> Unit = {}
) : PagingSource<Int, Track>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val page = params.key ?: 1
        return try {
            if (keyword.isBlank()) {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
            val (tracks, total) = repo.searchPageWithTotal(keyword, page)
            onTotalLoaded(total)
            val nextKey = if (tracks.isEmpty()) null else page + 1
            val prevKey = if (page == 1) null else page - 1
            LoadResult.Page(data = tracks, prevKey = prevKey, nextKey = nextKey)
        } catch (e: BiliRateLimitedException) {
            // 412 风控：与首页同策略，先退避再交 Error，掐断连击风暴
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

    companion object {
        const val RATE_LIMIT_BACKOFF_MS = 2000L
    }

    override fun getRefreshKey(state: PagingState<Int, Track>): Int? {
        return state.anchorPosition?.let { pos ->
            val page = state.closestPageToPosition(pos)
            page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
        }
    }
}
