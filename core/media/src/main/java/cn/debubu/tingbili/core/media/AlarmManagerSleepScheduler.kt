package cn.debubu.tingbili.core.media

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * AlarmManager 实现：`setAndAllowWhileIdle` + `RTC_WAKEUP`。
 *
 * 选它的理由：
 * - Doze 会**忽略 WakeLock**，也会把普通闹钟推迟到维护窗口；只有
 *   `setAndAllowWhileIdle` / `setExactAndAllowWhileIdle` 能在 Doze 里投递；
 * - 这里只需要"大致到点"，用非精确那条即可，**不需要**「闹钟和提醒」权限；
 * - 到点只会给本 App 投递一个广播，不亮屏、不响铃、不进闹钟列表。
 *
 * 约束：Doze 中每 App 9 分钟最多触发一次——对一晚一次的睡眠定时无影响。
 */
class AlarmManagerSleepScheduler(context: Context) : SleepAlarmScheduler {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(triggerAtMs: Long) {
        val am = alarmManager ?: return
        // 非精确：允许系统把触发时刻前后挪动（合并唤醒省电），睡眠定时可接受
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent())
    }

    override fun cancel() {
        alarmManager?.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, SleepTimerAlarmReceiver::class.java)
            .setAction(SleepTimerAlarmReceiver.ACTION_SLEEP_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        /** 固定 requestCode：schedule/cancel 必须命中同一条 PendingIntent */
        const val REQUEST_CODE = 0x51EE
    }
}
