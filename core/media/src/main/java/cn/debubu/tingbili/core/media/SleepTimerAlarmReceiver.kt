package cn.debubu.tingbili.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 睡眠定时的系统闹钟出口：Doze 里唯一能保证执行的路径（见 [AlarmManagerSleepScheduler]）。
 *
 * 只做一件事：把"到点"交给同一进程里的 [PlayerManager]（它持有真正的播放队列与状态），
 * 由它走 `pause()`。这里不连 MediaController、不发服务命令——广播窗口只有十几秒，
 * 异步连接很容易超时。
 */
@AndroidEntryPoint
class SleepTimerAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var playerManager: PlayerManager

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SLEEP_ALARM) return
        // goAsync：让系统知道我们还要跑一小段协程，别在 onReceive 返回后就回收进程
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                playerManager.onSleepAlarmFired()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SLEEP_ALARM = "cn.debubu.tingbili.action.SLEEP_TIMER_ALARM"
    }
}
