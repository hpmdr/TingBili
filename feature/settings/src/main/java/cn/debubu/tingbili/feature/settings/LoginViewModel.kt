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
    data class Ready(val url: String, val secondsLeft: Int) : QrUi
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

    fun startQr() {
        workJob?.cancel()
        _qr.value = QrUi.Generating
        workJob = viewModelScope.launch {
            val res = auth.generateQr()
            val data = res.getOrElse {
                _qr.value = QrUi.Failed(it.message ?: "二维码生成失败（可能是网络/风控，412 可换网重试）")
                return@launch
            }
            _qr.value = QrUi.Ready(data.url, QR_TTL_SEC)
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
            while (true) {
                delay(POLL_INTERVAL_MS)
                when (val st = auth.pollQr(data.qrcodeKey)) {
                    is QrStatus.Waiting, is QrStatus.Scanned -> Unit
                    is QrStatus.Expired -> {
                        _qr.value = QrUi.Failed("二维码已过期，请刷新")
                        countJob.cancel()
                        return@launch
                    }
                    is QrStatus.Success -> {
                        val r = auth.finishQrLogin(st.redirectUrl)
                        r.onSuccess { _qr.value = QrUi.Success }
                            .onFailure { _qr.value = QrUi.Failed("登录失败：${it.message}") }
                        countJob.cancel()
                        return@launch
                    }
                    is QrStatus.Failed -> {
                        _qr.value = QrUi.Failed(st.message)
                        countJob.cancel()
                        return@launch
                    }
                }
            }
        }
    }

    fun stopQr() {
        workJob?.cancel()
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

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }

    override fun onCleared() {
        stopQr()
    }

    companion object {
        const val QR_TTL_SEC = 180
        const val POLL_INTERVAL_MS = 2000L
    }
}
