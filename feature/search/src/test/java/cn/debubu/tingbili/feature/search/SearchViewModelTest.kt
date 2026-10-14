package cn.debubu.tingbili.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.SearchData
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SearchItem
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesRepository(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile("test_search_prefs")
            }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onSearch trims keyword`() {
        val vm = SearchViewModel(SavedStateHandle(), BiliRepository(FakeBiliApi()), prefs)

        vm.onSearch("  音乐  ")

        assertEquals("音乐", vm.keyword.value)
    }

    @Test
    fun `defaultKeyword falls back to first recommend keyword`() = runTest(dispatcher) {
        val vm = SearchViewModel(SavedStateHandle(), BiliRepository(FakeBiliApi()), prefs)

        assertEquals("有声小说", vm.defaultKeyword.first { it.isNotBlank() })
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

// helper extension for preferencesDataStoreFile in test
private fun android.content.Context.preferencesDataStoreFile(name: String): java.io.File =
    java.io.File(cacheDir, "$name.preferences_pb")
