package cn.debubu.tingbili.feature.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRepository

class HomePagingSource(
    private val repo: BiliRepository
) : PagingSource<Int, Track>() {

    companion object {
        /** 首页固定策展关键词，保证发现流贴合听书/有声小说定位。 */
        const val DEFAULT_RECOMMEND_KEYWORD = "有声小说"
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val page = params.key ?: 1
        return try {
            val tracks = repo.searchPage(DEFAULT_RECOMMEND_KEYWORD, page)
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
