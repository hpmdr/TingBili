package cn.debubu.tingbili.feature.space

import androidx.lifecycle.SavedStateHandle
import cn.debubu.tingbili.data.bilibili.SpaceApi
import cn.debubu.tingbili.data.bilibili.SpaceRepository
import cn.debubu.tingbili.data.bilibili.dto.RelationStatData
import cn.debubu.tingbili.data.bilibili.dto.RelationStatDto
import cn.debubu.tingbili.data.bilibili.dto.SpaceInfoData
import cn.debubu.tingbili.data.bilibili.dto.SpaceInfoDto
import cn.debubu.tingbili.data.bilibili.dto.SpaceVideosDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * UP 主页状态机的最小回归网（该模块此前零测试，见 REVIEW_REPORT I-03）。
 * 覆盖：头部信息三态（成功/降级/失败）、mid 缺失快速失败、排序与搜索词的状态语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpaceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeSpaceApi : SpaceApi {
        var infoResult: SpaceInfoDto = SpaceInfoDto()
        var statResult: RelationStatDto = RelationStatDto()
        var infoCalls = 0

        override suspend fun info(mid: Long, platform: String, webLocation: String): SpaceInfoDto {
            infoCalls++
            return infoResult
        }

        override suspend fun stat(vmid: Long): RelationStatDto = statResult

        override suspend fun videos(
            mid: Long,
            pn: Int,
            ps: Int,
            order: String,
            keyword: String,
            platform: String,
            webLocation: String,
            orderAvoided: Boolean,
        ): SpaceVideosDto = SpaceVideosDto()
    }

    private lateinit var api: FakeSpaceApi

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeSpaceApi()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(mid: Long): SpaceViewModel =
        SpaceViewModel(SavedStateHandle(mapOf("mid" to mid)), SpaceRepository(api))

    @Test
    fun `info success fills header with fans and following`() = runTest(dispatcher) {
        api.infoResult = SpaceInfoDto(
            code = 0,
            data = SpaceInfoData(mid = 42L, name = "某UP", face = "", sign = "签名", level = 5),
        )
        api.statResult = RelationStatDto(
            code = 0,
            data = RelationStatData(mid = 42L, follower = 1234L, following = 7L),
        )

        val vm = vm(42L)
        advanceUntilIdle()

        val info = vm.info.value
        assertEquals("某UP", info?.name)
        assertEquals(1234L, info?.fans)
        assertEquals(7L, info?.following)
        assertEquals(5, info?.level)
        assertFalse(vm.infoLoading.value)
        assertNull(vm.infoError.value)
    }

    @Test
    fun `stat failure degrades to zero instead of failing the page`() = runTest(dispatcher) {
        api.infoResult = SpaceInfoDto(code = 0, data = SpaceInfoData(mid = 42L, name = "某UP"))
        api.statResult = RelationStatDto(code = -404, message = "stat 挂了")

        val vm = vm(42L)
        advanceUntilIdle()

        assertEquals(0L, vm.info.value?.fans)
        assertNull(vm.infoError.value)
    }

    @Test
    fun `info error keeps message and clears loading`() = runTest(dispatcher) {
        api.infoResult = SpaceInfoDto(code = -412, message = "请求被拦截")

        val vm = vm(42L)
        advanceUntilIdle()

        assertEquals("请求被拦截", vm.infoError.value)
        assertFalse(vm.infoLoading.value)
        assertNull(vm.info.value)
    }

    @Test
    fun `missing mid fails fast without calling api`() = runTest(dispatcher) {
        val vm = vm(0L)
        advanceUntilIdle()

        assertEquals("UP 主信息缺失", vm.infoError.value)
        assertEquals(0, api.infoCalls)
    }

    @Test
    fun `setOrder switches order and resets total`() = runTest(dispatcher) {
        val vm = vm(42L)
        advanceUntilIdle()

        assertEquals(SpaceOrder.PUBDATE, vm.order.value)
        vm.setOrder(SpaceOrder.CLICK)
        assertEquals(SpaceOrder.CLICK, vm.order.value)
        assertEquals(0, vm.total.value)
    }

    @Test
    fun `search keyword is trimmed`() = runTest(dispatcher) {
        val vm = vm(42L)
        advanceUntilIdle()

        vm.setSearchKeyword("  原神  ")
        assertEquals("原神", vm.searchKeyword.value)
        vm.setSearchKeyword("")
        assertEquals("", vm.searchKeyword.value)
    }
}