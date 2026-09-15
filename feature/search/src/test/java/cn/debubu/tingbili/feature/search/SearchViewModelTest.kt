package cn.debubu.tingbili.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingSource
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.SearchData
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SearchItem
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchViewModelTest {

    @Test
    fun `onSearch trims keyword`() {
        val vm = SearchViewModel(SavedStateHandle(), BiliRepository(FakeBiliApi()))

        vm.onSearch("  音乐  ")

        assertEquals("音乐", vm.keyword.value)
    }

    @Test
    fun `blank keyword does not call api`() = runBlocking {
        val api = FakeBiliApi()
        val source = SearchPagingSource(BiliRepository(api), "   ")

        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        assertEquals(0, api.searchCalls)
    }

    @Test
    fun `keyword loads first page`() = runBlocking {
        val api = FakeBiliApi()
        val source = SearchPagingSource(BiliRepository(api), "音乐")

        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )

        assertTrue(result is PagingSource.LoadResult.Page)
        val page = result as PagingSource.LoadResult.Page<Int, Track>
        assertEquals(1, api.searchCalls)
        assertEquals("BV1xx", page.data.single().bvid)
        assertEquals(2, page.nextKey)
    }

    private class FakeBiliApi : BiliApi {
        var searchCalls = 0

        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto {
            searchCalls++
            return SearchDto(
                code = 0,
                data = SearchData(
                    result = listOf(
                        SearchItem(
                            bvid = "BV1xx",
                            title = keyword,
                            author = "up",
                            pic = "https://cover",
                            duration = "3:00"
                        )
                    ),
                    page = page,
                    numResults = 1,
                    numPages = 1
                )
            )
        }

        override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)

        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto =
            PlayUrlDto(code = 0)

        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto =
            SubtitleDto(code = 0)
    }
}
