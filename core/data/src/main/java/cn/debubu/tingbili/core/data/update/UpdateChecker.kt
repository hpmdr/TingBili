package cn.debubu.tingbili.core.data.update

import java.time.Instant

/**
 * 更新判定纯逻辑（无 Android / 无 IO，可单测）。
 *
 * 时间口径：远端取 release.published_at（发布时间，不是草稿创建时间），
 * 本地取打包瞬间墙钟（core:data BuildConfig.BUILD_TIME，秒）。
 */
object UpdateChecker {

    const val LATEST_URL = "https://api.github.com/repos/hpmdr/TingBili/releases/latest"

    /** assets 里找不到直链时的兜底：让浏览器打开 Releases 页，用户自己点。 */
    const val RELEASES_PAGE = "https://github.com/hpmdr/TingBili/releases/latest"

    /** 容差：打包机与 GitHub 时钟差 + 同一秒打出包的抖动，60s 内视为同一版本。 */
    const val TOLERANCE_SEC = 60L

    /** 自动检查节流：每 12 小时最多一次（手动入口不受限）。 */
    const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** "2026-11-04T12:34:56Z" → 秒；draft/解析失败返回 null（调用方视为无更新）。 */
    fun publishedAtSec(iso: String): Long? =
        try {
            Instant.parse(iso).epochSecond
        } catch (_: Exception) {
            null
        }

    /**
     * 从 assets 挑安装包直链：TingBili_{version}_android.apk。
     * release.yml 会同传 .sha256 校验文件，必须排除；找不到返回 null（调用方跳 RELEASES_PAGE）。
     */
    fun pickApkUrl(assets: List<GitHubAsset>): String? =
        assets.firstOrNull { a ->
            a.name.endsWith(".apk", ignoreCase = true) &&
                !a.name.endsWith(".sha256", ignoreCase = true) &&
                a.downloadUrl.isNotBlank()
        }?.downloadUrl
}
