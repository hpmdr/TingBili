package cn.debubu.tingbili.feature.settings

import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import cn.debubu.tingbili.data.bilibili.auth.QrData
import cn.debubu.tingbili.data.bilibili.auth.QrStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 轮询容错回归：单次/少量失败不断码（带提示继续轮询），
 * 连续失败达阈值才判死刑。覆盖“二维码闪一下就没”的报障。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = object : AuthStore {
        override fun load(): BiliSession? = null
        override fun save(session: BiliSession?) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeAuth(polls: List<QrStatus>): AuthRepository =
        object : AuthRepository(Json { ignoreUnknownKeys = true }, store) {
            override suspend fun generateQr(): Result<QrData> =
                Result.success(QrData("K", "https://qr/x"))

            private var pollIndex = 0
            override suspend fun pollQr(qrcodeKey: String): QrStatus =
                polls.getOrElse(pollIndex++) { polls.last() }

            override suspend fun finishQrLogin(redirectUrl: String): Result<BiliSession> =
                Result.success(BiliSession("S", "J", 7L))
        }

    @Test
    fun `transient poll failures keep qr alive`() = runTest(dispatcher) {
        // 前两次失败（风控空回包），随后恢复 Waiting
        val vm = LoginViewModel(fakeAuth(listOf(
            QrStatus.Failed("empty redirect url"),
            QrStatus.Failed("empty redirect url"),
            QrStatus.Waiting,
        )))
        vm.startQr()
        advanceTimeBy(1_500)
        val ready = vm.qr.value
        assertTrue(ready is QrUi.Ready)
        // 两次失败后：还是 Ready，只是带提示
        advanceTimeBy(3_000)
        val retrying = vm.qr.value
        assertTrue(retrying is QrUi.Ready)
        assertEquals("网络波动，自动重试中…", (retrying as QrUi.Ready).notice)
        // 恢复 Waiting 后提示清除，二维码仍在
        advanceTimeBy(3_000)
        val recovered = vm.qr.value
        assertTrue(recovered is QrUi.Ready)
        assertNull((recovered as QrUi.Ready).notice)
    }

    @Test
    fun `persistent poll failures terminate after threshold`() = runTest(dispatcher) {
        val vm = LoginViewModel(fakeAuth(listOf(QrStatus.Failed("boom"))))
        vm.startQr()
        advanceTimeBy(5_000)
        // 阈值内：Ready + 提示
        val retrying = vm.qr.value
        assertTrue(retrying is QrUi.Ready)
        assertTrue((retrying as QrUi.Ready).notice != null)
        // 连续失败达阈值：判死刑
        advanceTimeBy(8_000)
        val terminal = vm.qr.value
        assertTrue(terminal is QrUi.Failed)
    }
}
