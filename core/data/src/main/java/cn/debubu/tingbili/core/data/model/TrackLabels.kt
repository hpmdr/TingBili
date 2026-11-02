package cn.debubu.tingbili.core.data.model

/**
 * 分 P 标签：多 P 且序号已知时给 `P{n}`，单 P/同名标题（BV 标题就是分 P 名）时返回 null。
 *
 * 判定只看 `videoTitle` 与 `title` 是否同名，不依赖 pageCount——
 * 收藏夹里的 Track 常没有真实页数（默认 1），但 P 序号仍然有效。
 *
 * 规则放 core:data 是为了让 core:ui（页面/列表）和 core:media（通知栏 MediaMetadata）
 * 共用同一口径，避免两边漂移。
 */
fun episodePageLabel(title: String, videoTitle: String?, pageIndex: Int?): String? {
    val singlePart = videoTitle == null || videoTitle == title
    return pageIndex?.takeIf { it > 0 && !(it == 1 && singlePart) }?.let { "P$it" }
}

/**
 * 带分 P 前缀的完整分集标题：`P17 · 295-308`。
 * 单 P/无序号时就是原标题；标题为空时退化到 bvid。
 */
fun Track.episodeLabel(): String {
    val name = title.ifBlank { bvid }
    val page = episodePageLabel(name, videoTitle, pageIndex)
    return page?.let { "$it · $name" } ?: name
}

/**
 * 队列序号标题：`03 · 295-308`（序号是该条在当前播放列表的位置，从 1 开始）。
 *
 * 混播收藏夹里各条分属不同 BV，`P{n}` 各说各话；队列序号才是所有来源的统一口径。
 * 播放页章节行、迷你条副标题、通知栏标题都用它；`queueIndex` 为 0-based 下标。
 */
fun Track.queueLabel(queueIndex: Int): String {
    val name = title.ifBlank { bvid }
    return String.format(java.util.Locale.ROOT, "%02d · %s", queueIndex + 1, name)
}
