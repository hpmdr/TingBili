package cn.debubu.tingbili.core.media

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer — after [durationMs] pause playback.
 * Spec: timerManager.set(durationMs) -> coroutine delay then player.pause()
 */
@Singleton
class TimerManager @Inject constructor(
    private val player: PlayerHandle,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs

    /** For testing — allow injecting a test scope. */
    internal var testScope: CoroutineScope? = null
    private fun effectiveScope(): CoroutineScope = testScope ?: scope

    fun set(durationMs: Long) {
        job?.cancel()
        if (durationMs <= 0L) {
            _remainingMs.value = null
            job = null
            return
        }
        _remainingMs.value = durationMs
        job = effectiveScope().launch {
            delay(durationMs)
            player.pause()
            _remainingMs.value = null
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = null
    }
}
