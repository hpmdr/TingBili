package cn.debubu.tingbili.core.media

/**
 * 睡眠定时的"到点提醒"口子，把系统能力从 [SleepTimer] 的纯逻辑里隔离出来。
 *
 * 为什么需要它：协程 `delay` 走 uptime（不含深睡），DSP offload 下 CPU 整夜深睡时
 * 倒计时几乎不前进，到点没人触发（2026-11 实机反馈：定时设了却播了一整晚）。
 * 生产实现走 AlarmManager 的 allow-while-idle 闹钟（Doze 也能投递）；
 * 单测注入 [None] 或 Fake，验证调度时机。
 */
interface SleepAlarmScheduler {

    /** 预约 [triggerAtMs]（墙钟毫秒）的一次性唤醒；重复调用应覆盖旧预约。 */
    fun schedule(triggerAtMs: Long)

    /** 取消未触发的预约；没有预约时应当是安全的空操作。 */
    fun cancel()

    /** 无系统依赖的替身：定时退化成纯协程行为（单测/预览用）。 */
    object None : SleepAlarmScheduler {
        override fun schedule(triggerAtMs: Long) {}
        override fun cancel() {}
    }
}
