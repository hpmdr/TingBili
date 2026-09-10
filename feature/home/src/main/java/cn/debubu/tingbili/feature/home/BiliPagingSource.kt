package cn.debubu.tingbili.feature.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRepository

class BiliPagingSource(
    private val repo: BiliRepository,
    private val keyword: String
) : PagingSource<Int, Track>() {

    companion object {
        /** 空搜时用作默认推荐的策展关键词，保证首页贴合听书/有声小说定位。 */
        const val DEFAULT_RECOMMEND_KEYWORD = "有声小说"
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val page = params.key ?: 1
        return try {
            val effectiveKeyword = keyword.ifBlank { DEFAULT_RECOMMEND_KEYWORD }
            val tracks = repo.searchPage(effectiveKeyword, page)
            val nextKey = if (tracks.isEmpty()) null else page + 1
            val prevKey = if (page == 1) null else page - 1
            LoadResult.Page(data = tracks, prevKey = prevKey, nextKey = nextKey)
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
}
