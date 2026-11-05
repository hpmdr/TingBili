package cn.debubu.tingbili.core.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub Releases latest 返回的单个 asset（只取更新需要的两个字段）。 */
@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url")
    val downloadUrl: String = "",
)

/**
 * `GET /repos/hpmdr/TingBili/releases/latest` 的子集。
 * 只比时间（published_at），tag 仅展示：tag 手滑打错（0.2 → 0.1.1）时字符串比较会漏报。
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tag: String = "",
    val body: String? = null,
    @SerialName("published_at")
    val publishedAt: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)
