package cn.debubu.tingbili.feature.home

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliRepository

/**
 * 首页发现流：策展关键词分页轮换（第 1 页搜词 1、第 2 页搜词 2……循环），
 * 关键词列表来自设置（DataStore），增减后自动刷新。
 *
 * @param onFirstPage 首页加载成功后的回调（SWR 快照用），调用方负责落盘，异常内部吞掉不影响分页。
 */
class HomePagingSource(
    private val repo: BiliRepository,
    private val keywords: List<String> = emptyList(),
    private val onFirstPage: ((tracks: List<Track>, orderedKeywords: List<String>) -> Unit)? = null
) : PagingSource<Int, Track>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val page = params.key ?: 1
        return try {
            val kws = keywords.ifEmpty { DEFAULT_RECOMMEND_KEYWORDS }
            val keyword = kws[(page - 1) % kws.size]
            val tracks = repo.searchPage(keyword, page)
            val nextKey = if (tracks.isEmpty()) null else page + 1
            val prevKey = if (page == 1) null else page - 1
            if (page == 1 && tracks.isNotEmpty()) {
                try {
                    onFirstPage?.invoke(tracks, kws)
                } catch (_: Exception) {
                    // 快照回调永不打断分页
                }
            }
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

    companion object {
        /** 默认策展关键词（与 PreferencesRepository.DEFAULT_HOME_KEYWORDS 一致，用于兜底） */
        val DEFAULT_RECOMMEND_KEYWORDS = listOf("有声小说", "有声书", "悬疑小说", "多人有声")
    }
}
