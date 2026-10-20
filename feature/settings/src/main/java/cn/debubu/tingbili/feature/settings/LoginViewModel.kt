package cn.debubu.tingbili.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import cn.debubu.tingbili.data.bilibili.auth.QrStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QrUi {
    data object Idle : QrUi
    data object Generating : QrUi
    data class Ready(val url: String, val secondsLeft: Int, val notice: String? = null) : QrUi
    data object Success : QrUi
    data class Failed(val message: String) : QrUi
}

sealed interface CookieUi {
    data object Idle : CookieUi
    data object Verifying : CookieUi
    data class Failed(val message: String) : CookieUi
}

/** 登录页状态机：Web 扫码（主）+ Cookie 粘贴（兜底）。短信登录另立项，不在此展开。 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    val session: StateFlow<BiliSession?> = auth.sessionFlow

    private val _qr = MutableStateFlow<QrUi>(QrUi.Idle)
    val qr: StateFlow<QrUi> = _qr.asStateFlow()

    private val _cookie = MutableStateFlow<CookieUi>(CookieUi.Idle)
    val cookie: StateFlow<CookieUi> = _cookie.asStateFlow()

    private var workJob: Job? = null

    /** 当前码的 key（暂停后恢复轮询用）；手动刷新/重取时清空 */
    private var qrKey: String? = null

    /** 切走暂停的时间点（切回时扣掉离开的秒数，过期了就重取） */
    private var qrPausedAtMs: Long? = null

    /** 测试可注入时钟，默认真时间 */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    fun startQr() {
        workJob?.cancel()
        qrKey = null
        qrPausedAtMs = null
        _qr.value = QrUi.Generating
        viewModelScope.launch {
            val res = auth.generateQr()
            val data = res.getOrElse {
                _qr.value = QrUi.Failed(it.message ?: "二维码生成失败（可能是网络/风控，412 可换网重试）")
                return@launch
            }
            qrKey = data.qrcodeKey
            _qr.value = QrUi.Ready(data.url, QR_TTL_SEC)
            launchQrLoop(data.qrcodeKey)
        }
    }

    /**
     * 切回扫码页：码还有效就地续跑（倒计时扣掉离开的时间，不重取、不换码），
     * 失效/失败/初始态则重取。Success 保持不动。
     */
    fun resumeQr() {
        val cur = _qr.value
        if (cur is QrUi.Success) return
        val key = qrKey
        if (cur is QrUi.Ready && key != null) {
            val elapsedSec = qrPausedAtMs?.let { ((clock() - it) / 1000).toInt().coerceAtLeast(0) } ?: 0
            val remaining = cur.secondsLeft - elapsedSec
            if (remaining > RESUME_MIN_SEC) {
                _qr.value = cur.copy(secondsLeft = remaining)
                launchQrLoop(key)
                return
            }
        }
        startQr()
    }

    private fun launchQrLoop(qrcodeKey: String) {
        workJob?.cancel()
        qrPausedAtMs = null
        workJob = viewModelScope.launch {
            val countJob = launch {
                while (true) {
                    delay(1000)
                    val cur = _qr.value as? QrUi.Ready ?: return@launch
                    if (cur.secondsLeft <= 1) {
                        _qr.value = QrUi.Failed("二维码已过期，请刷新")
                        workJob?.cancel()
                        return@launch
                    }
                    _qr.value = cur.copy(secondsLeft = cur.secondsLeft - 1)
                }
            }
            // 对齐 PiliPlus：轮询出错只改状态文案，码一直在、一直轮询到 180 秒；
            // 杀死二维码的只有倒计时走完和 86038 过期
            while (true) {
                delay(POLL_INTERVAL_MS)
                when (val st = auth.pollQr(qrcodeKey)) {
                    is QrStatus.Waiting, is QrStatus.Scanned -> {
                        (_qr.value as? QrUi.Ready)?.let {
                            if (it.notice != null) _qr.value = it.copy(notice = null)
                        }
                    }
                    is QrStatus.Expired -> {
                        _qr.value = QrUi.Failed("二维码已过期，请刷新")
                        countJob.cancel()
                        return@launch
                    }
                    is QrStatus.Success -> {
                        val r = auth.finishWebLogin(st.redirectUrl, st.refreshToken)
                        r.onSuccess { _qr.value = QrUi.Success }
                            .onFailure { _qr.value = QrUi.Failed("登录失败：${it.message}") }
                        countJob.cancel()
                        return@launch
                    }
                    is QrStatus.Failed -> {
                        (_qr.value as? QrUi.Ready)?.let {
                            _qr.value = it.copy(notice = "网络波动，自动重试中…")
                        }
                    }
                }
            }
        }
    }

    /** 切走停轮询（省请求、防风控）：码还在就记下时间点，切回 resumeQr 续跑 */
    fun stopQr() {
        workJob?.cancel()
        if (_qr.value is QrUi.Ready) qrPausedAtMs = clock()
    }

    fun loginWithCookies(raw: String) {
        if (raw.isBlank()) {
            _cookie.value = CookieUi.Failed("请先粘贴 Cookie")
            return
        }
        _cookie.value = CookieUi.Verifying
        viewModelScope.launch {
            auth.loginWithCookies(raw)
                .onSuccess { _cookie.value = CookieUi.Idle }
                .onFailure { _cookie.value = CookieUi.Failed(it.message ?: "验证失败") }
        }
    }

    // ── 短信 / 密码 / 极验（照抄 PiliPlus 规则 + biu 的 web 参数） ──

    sealed interface SmsUi {
        data object Idle : SmsUi
        data object Sending : SmsUi
        /** 已发送：secondsLeft 重发倒计时，captchaKey 登录用，sentAtMs 算 5 分钟过期 */
        data class Sent(val secondsLeft: Int, val captchaKey: String, val sentAtMs: Long) : SmsUi
        data class Failed(val message: String) : SmsUi
    }

    sealed interface PasswordUi {
        data object Idle : PasswordUi
        data object Working : PasswordUi
        data class Failed(val message: String) : PasswordUi
    }

    /** 极验弹窗：Showing 时 UI 弹 WebView，滑完调 onGeeResult，关掉调 onGeeCancel */
    sealed interface GeeUi {
        data object Hidden : GeeUi
        data class Showing(val gt: String, val challenge: String) : GeeUi
    }

    private val _sms = MutableStateFlow<SmsUi>(SmsUi.Idle)
    val sms: StateFlow<SmsUi> = _sms.asStateFlow()

    private val _password = MutableStateFlow<PasswordUi>(PasswordUi.Idle)
    val password: StateFlow<PasswordUi> = _password.asStateFlow()

    private val _gee = MutableStateFlow<GeeUi>(GeeUi.Hidden)
    val gee: StateFlow<GeeUi> = _gee.asStateFlow()

    private var geeTicket: AuthRepository.GeeTicket? = null
    private var pendingGeeAction: ((AuthRepository.GeeResult) -> Unit)? = null
    private var smsCooldownJob: Job? = null

    /** 短信：先拿极验票据 → 弹滑块 → 滑完自动发短信 */
    fun sendSms(cid: Int, tel: String) {
        if (tel.isBlank()) {
            _sms.value = SmsUi.Failed("手机号不能为空")
            return
        }
        viewModelScope.launch {
            _sms.value = SmsUi.Sending
            val ticket = auth.fetchGeeTicket().getOrElse {
                _sms.value = SmsUi.Failed(it.message ?: "获取验证信息失败")
                return@launch
            }
            geeTicket = ticket
            pendingGeeAction = { result -> doSendSms(cid, tel.trim(), ticket, result) }
            _gee.value = GeeUi.Showing(ticket.gt, ticket.challenge)
        }
    }

    private fun doSendSms(cid: Int, tel: String, ticket: AuthRepository.GeeTicket, result: AuthRepository.GeeResult) {
        viewModelScope.launch {
            _sms.value = SmsUi.Sending
            auth.sendSms(cid, tel, ticket, result)
                .onSuccess { key ->
                    startSmsCooldown(SmsUi.Sent(60, key, System.currentTimeMillis()))
                }
                .onFailure { _sms.value = SmsUi.Failed(it.message ?: "发送失败") }
        }
    }

    private fun startSmsCooldown(sent: SmsUi.Sent) {
        smsCooldownJob?.cancel()
        _sms.value = sent
        smsCooldownJob = viewModelScope.launch {
            var left = sent.secondsLeft
            while (left > 0) {
                delay(1000)
                left--
                val cur = _sms.value
                if (cur is SmsUi.Sent) _sms.value = cur.copy(secondsLeft = left) else return@launch
            }
        }
    }

    /** 短信登录：照抄 PiliPlus 三道校验（取过码 / 填过码 / 5 分钟有效期） */
    fun submitSms(cid: Int, tel: String, code: String) {
        val sent = _sms.value as? SmsUi.Sent
        if (sent == null || sent.captchaKey.isBlank()) {
            _sms.value = SmsUi.Failed("请先点击获取验证码")
            return
        }
        if (code.isBlank()) {
            _sms.value = SmsUi.Failed("验证码不能为空")
            return
        }
        if (System.currentTimeMillis() - sent.sentAtMs > SMS_CODE_TTL_MS) {
            _sms.value = SmsUi.Failed("验证码已过期，请重新获取")
            return
        }
        viewModelScope.launch {
            auth.smsLogin(cid, tel.trim(), code.trim(), sent.captchaKey)
                .onSuccess { _sms.value = SmsUi.Idle }
                .onFailure { _sms.value = SmsUi.Failed(it.message ?: "登录失败") }
        }
    }

    /** 密码登录：先拿极验票据 → 弹滑块 → 滑完自动登录（RSA 加密在仓储层） */
    fun submitPassword(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _password.value = PasswordUi.Failed("用户名或密码不能为空")
            return
        }
        viewModelScope.launch {
            _password.value = PasswordUi.Working
            val ticket = auth.fetchGeeTicket().getOrElse {
                _password.value = PasswordUi.Failed(it.message ?: "获取验证信息失败")
                return@launch
            }
            geeTicket = ticket
            pendingGeeAction = { result ->
                viewModelScope.launch {
                    auth.passwordLogin(username.trim(), password, ticket, result)
                        .onSuccess { _password.value = PasswordUi.Idle }
                        .onFailure { _password.value = PasswordUi.Failed(it.message ?: "登录失败") }
                }
            }
            _password.value = PasswordUi.Idle
            _gee.value = GeeUi.Showing(ticket.gt, ticket.challenge)
        }
    }

    fun onGeeResult(validate: String, seccode: String, challenge: String) {
        _gee.value = GeeUi.Hidden
        pendingGeeAction?.invoke(AuthRepository.GeeResult(validate, seccode, challenge))
        pendingGeeAction = null
    }

    fun onGeeCancel() {
        _gee.value = GeeUi.Hidden
        pendingGeeAction = null
        if (_sms.value is SmsUi.Sending) _sms.value = SmsUi.Idle
        if (_password.value is PasswordUi.Working) _password.value = PasswordUi.Idle
    }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }

    override fun onCleared() {
        stopQr()
    }

    companion object {
        const val QR_TTL_SEC = 180
        const val POLL_INTERVAL_MS = 2000L
        /** 切回时剩余秒数高于此值才续跑，否则直接重取（剩几秒的码没扫码价值） */
        const val RESUME_MIN_SEC = 10
        /** 短信验证码有效期：照抄 PiliPlus 的 5 分钟 */
        const val SMS_CODE_TTL_MS = 5 * 60 * 1000L
    }
}
