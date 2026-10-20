package cn.debubu.tingbili.feature.mine

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
import kotlinx.coroutines.delay
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

    private inner class FakeAuth(val polls: List<QrStatus>) :
        AuthRepository(Json { ignoreUnknownKeys = true }, store) {
        var generateCalls = 0
        var ticketCalls = 0
        var smsSendCalls = 0
        var smsLoginCalls = 0
        var ticketDelayMs = 0L
        var sendDelayMs = 0L
        var loginDelayMs = 0L
        private var pollIndex = 0
        override suspend fun generateQr(): Result<QrData> {
            generateCalls++
            return Result.success(QrData("K", "https://qr/x"))
        }
        override suspend fun pollQr(qrcodeKey: String): QrStatus =
            polls.getOrElse(pollIndex++) { polls.last() }
        override suspend fun finishWebLogin(redirectUrl: String, refreshToken: String): Result<BiliSession> =
            Result.success(BiliSession("S", "J", 7L))
        override suspend fun fetchGeeTicket(): Result<AuthRepository.GeeTicket> {
            ticketCalls++
            if (ticketDelayMs > 0) delay(ticketDelayMs)
            return Result.success(AuthRepository.GeeTicket("t", "g", "c"))
        }
        override suspend fun sendSms(
            cid: Int, tel: String,
            ticket: AuthRepository.GeeTicket, result: AuthRepository.GeeResult
        ): Result<String> {
            smsSendCalls++
            if (sendDelayMs > 0) delay(sendDelayMs)
            return Result.success("CAPTCHA_KEY")
        }
        override suspend fun smsLogin(cid: Int, tel: String, code: String, captchaKey: String): Result<BiliSession> {
            smsLoginCalls++
            if (loginDelayMs > 0) delay(loginDelayMs)
            return Result.success(BiliSession("S", "J", 7L))
        }
    }

    private fun fakeAuth(polls: List<QrStatus>) = FakeAuth(polls)

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
    fun `persistent poll failures never kill qr before ttl`() = runTest(dispatcher) {
        val vm = LoginViewModel(fakeAuth(listOf(QrStatus.Failed("boom"))))
        vm.startQr()
        advanceTimeBy(5_000)
        // 一直失败：码还在，只带提示
        val retrying = vm.qr.value
        assertTrue(retrying is QrUi.Ready)
        assertTrue((retrying as QrUi.Ready).notice != null)
        // 撑满 180 秒：倒计时走完才判过期
        advanceTimeBy(180_000)
        val terminal = vm.qr.value
        assertTrue(terminal is QrUi.Failed)
    }

    @Test
    fun `stop freezes countdown while away`() = runTest(dispatcher) {
        val vm = LoginViewModel(fakeAuth(listOf(QrStatus.Waiting)))
        vm.startQr()
        advanceTimeBy(2_500)
        assertEquals(178, (vm.qr.value as QrUi.Ready).secondsLeft)
        vm.stopQr()
        advanceTimeBy(10_000)
        // 停了就不走字：还是 178
        assertEquals(178, (vm.qr.value as QrUi.Ready).secondsLeft)
    }

    @Test
    fun `resume continues countdown without regenerating`() = runTest(dispatcher) {
        val auth = fakeAuth(listOf(QrStatus.Waiting))
        val vm = LoginViewModel(auth)
        vm.startQr()
        advanceTimeBy(3_500)
        assertEquals(177, (vm.qr.value as QrUi.Ready).secondsLeft)
        vm.stopQr()
        vm.resumeQr() // 真时间几乎没走：扣 0 秒，原码续跑
        advanceTimeBy(2_500)
        assertEquals(1, auth.generateCalls)
        assertEquals(175, (vm.qr.value as QrUi.Ready).secondsLeft)
    }

    @Test
    fun `resume after expiry regenerates`() = runTest(dispatcher) {
        val auth = fakeAuth(listOf(QrStatus.Waiting))
        val vm = LoginViewModel(auth)
        var nowMs = 1_000_000L
        vm.clock = { nowMs }
        vm.startQr()
        advanceTimeBy(1_500)
        vm.stopQr()
        nowMs += 200_000 // 离开 200 秒：码早过期了
        vm.resumeQr()
        advanceTimeBy(1_500)
        assertEquals(2, auth.generateCalls)
        assertEquals(179, (vm.qr.value as QrUi.Ready).secondsLeft)
    }

    @Test
    fun `sendSms double tap fetches ticket once`() = runTest(dispatcher) {
        val auth = fakeAuth(listOf(QrStatus.Waiting))
        auth.ticketDelayMs = 1_000
        val vm = LoginViewModel(auth)
        vm.sendSms(86, "13800000000")
        vm.sendSms(86, "13800000000")
        advanceTimeBy(500)
        assertEquals(1, auth.ticketCalls)
        advanceTimeBy(1_000)
        assertEquals(1, auth.ticketCalls)
        assertTrue(vm.gee.value is LoginViewModel.GeeUi.Showing)
    }

    @Test
    fun `submitSms double tap logins once with Submitting state`() = runTest(dispatcher) {
        val auth = fakeAuth(listOf(QrStatus.Waiting))
        auth.sendDelayMs = 1_000
        auth.loginDelayMs = 2_000
        val vm = LoginViewModel(auth)
        // 先走完发码流程
        vm.sendSms(86, "13800000000")
        advanceTimeBy(500)
        vm.onGeeResult("v", "s", "c")
        advanceTimeBy(1_500)
        assertTrue(vm.sms.value is LoginViewModel.SmsUi.Sent)
        // 连点登录两次：第二次必须被吞掉，且中间有 Submitting 忙态
        vm.submitSms(86, "13800000000", "123456")
        vm.submitSms(86, "13800000000", "123456")
        // 忙态同步可见，不依赖 pump；且第二次没冲掉它
        assertEquals(LoginViewModel.SmsUi.Submitting, vm.sms.value)
        advanceTimeBy(100)
        assertEquals(1, auth.smsLoginCalls)
        assertTrue(vm.sms.value is LoginViewModel.SmsUi.Submitting)
        advanceTimeBy(3_000)
        assertEquals(1, auth.smsLoginCalls)
        assertTrue(vm.sms.value is LoginViewModel.SmsUi.Idle)
    }

    @Test
    fun `submitPassword double tap fetches ticket once`() = runTest(dispatcher) {
        val auth = fakeAuth(listOf(QrStatus.Waiting))
        auth.ticketDelayMs = 1_000
        val vm = LoginViewModel(auth)
        vm.submitPassword("13800000000", "secret")
        vm.submitPassword("13800000000", "secret")
        advanceTimeBy(500)
        assertEquals(1, auth.ticketCalls)
        assertTrue(vm.password.value is LoginViewModel.PasswordUi.Working)
    }
}
