package cn.debubu.tingbili.core.data.update

/**
 * 启动自动检查的门控（无 Android / 无 IO，可单测）。
 *
 * 拆两段对应实际执行顺序：先判「要不要发请求」，发完拿到远端 release 再判「要不要弹窗」。
 * 分两段是因为 `lastUpdateCheckMs` 在发请求前就写盘——请求失败也认这次节流，
 * 避免离线时每次冷启动都去打 GitHub（匿名限额 60 次/小时）。
 */
object AutoUpdateGate {

    /** 是否到了该发请求的时候。 */
    fun shouldFetch(
        debuggable: Boolean,
        autoCheckEnabled: Boolean,
        nowMs: Long,
        lastCheckMs: Long,
    ): Boolean {
        if (debuggable) return false
        if (!autoCheckEnabled) return false
        return nowMs - lastCheckMs >= UpdateChecker.AUTO_CHECK_INTERVAL_MS
    }

    /**
     * 拿到远端 release 后是否弹窗。
     *
     * @param skippedReleaseSec 用户点过"不再提醒此版"的远端发布时间（0 = 没跳过）
     */
    fun shouldPrompt(
        skippedReleaseSec: Long,
        localVersion: String,
        remoteTag: String,
        remotePublishedSec: Long?,
        localBuildSec: Long,
    ): Boolean {
        if (remotePublishedSec != null && remotePublishedSec == skippedReleaseSec) return false
        return UpdatePolicy.isUpdate(localVersion, remoteTag, remotePublishedSec, localBuildSec)
    }
}
