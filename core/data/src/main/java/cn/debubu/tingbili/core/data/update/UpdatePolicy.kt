package cn.debubu.tingbili.core.data.update

/**
 * 「远端这个 release 算不算我的新版」的判定（无 Android / 无 IO，可单测）。
 *
 * 双条件，缺一不可：
 * 1. **tag 不同**——本地 versionName 与远端 tag 去 `v` 后不相等；
 * 2. **时间更新**——远端 published_at 晚于本地打包墙钟（[UpdateChecker.TOLERANCE_SEC] 容差）。
 *
 * 为什么不只比时间：发版时序是 tag push → 构建（BUILD_TIME 在此定格）→ 上传 →
 * `gh release create`（published_at 在此才产生），实测 v0.2.12 两者差 265s，远超容差。
 * 于是已装该 release 的用户会被判成「有新版」，弹窗让下载同一个版本。
 * tag 相同则必然是同一个版本，直接否掉；时间条件顺带兜住 tag 手滑重发、
 * 以及本地比远端新的情况（回滚安装旧包）。
 */
object UpdatePolicy {

    /** 远端 tag 归一化：去掉前导 `v`/`V` 与首尾空白，供版本号逐段比较。 */
    fun normalizeTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * @param localVersion 本地 versionName（如 `0.2.12`）
     * @param remoteTag 远端 tag_name（如 `v0.2.12`）
     * @param remotePublishedSec 远端发布时间（秒），解析失败传 null 视为不可用
     * @param localBuildSec 本地打包墙钟（秒）
     */
    fun isUpdate(
        localVersion: String,
        remoteTag: String,
        remotePublishedSec: Long?,
        localBuildSec: Long,
    ): Boolean {
        if (remotePublishedSec == null) return false
        if (normalizeTag(remoteTag) == normalizeTag(localVersion)) return false
        return remotePublishedSec > localBuildSec + UpdateChecker.TOLERANCE_SEC
    }
}
