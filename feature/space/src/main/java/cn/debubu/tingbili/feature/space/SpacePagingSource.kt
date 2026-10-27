package cn.debubu.tingbili.feature.space

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.data.bilibili.SpaceRepository
import cn.debubu.tingbili.data.bilibili.dto.UpVideo

/**
 * UP 主投稿分页：一个 [order] 对应一条 Pager，切换排序时整体重建。
 * @param onTotal 首屏拿到的投稿总数（接口 page.count），用于头部展示
 */
class SpacePagingSource(
    private val repo: SpaceRepository,
    private val mid: Long,
    private val order: String,
    /** 空串 = 全部投稿；非空 = 只在 UP 投稿内按关键词过滤 */
    private val keyword: String = "",
    private val onTotal: (Int) -> Unit = {},
) : PagingSource<Int, UpVideo>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, UpVideo> {
        val page = params.key ?: 1
        return when (val r = repo.videos(mid, page, order, keyword)) {
            is Result.Success -> {
                val (videos, total) = r.data
                if (page == 1) onTotal(total)
                LoadResult.Page(
                    data = videos,
                    prevKey = if (page == 1) null else page - 1,
                    nextKey = if (videos.isEmpty()) null else page + 1,
                )
            }
            is Result.Error -> LoadResult.Error(IllegalStateException(r.msg, r.throwable))
        }
    }

    override fun getRefreshKey(state: PagingState<Int, UpVideo>): Int? =
        state.anchorPosition?.let { pos ->
            val page = state.closestPageToPosition(pos)
            page?.prevKey?.plus(1) ?: page?.nextKey?.minus(1)
        }
}
