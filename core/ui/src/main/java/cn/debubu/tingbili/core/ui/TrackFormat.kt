package cn.debubu.tingbili.core.ui

/** 时长格式化：`0:41` / `1:02:03` */
fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 播放进度文案（跟在“上次听到”后面用）：`P1 · 0:41 / 4:50:51`。
 * 分 P 序号未知（老数据/单集）时省略 `P{n} · `，总长未知时省略 ` / 总长`。
 */
fun playedProgressLabel(pageIndex: Int?, positionMs: Long, durationMs: Long? = null): String {
    val page = pageIndex?.takeIf { it > 0 }?.let { "P$it · " }.orEmpty()
    val total = durationMs?.takeIf { it > 0L }?.let { " / ${formatDurationMs(it)}" }.orEmpty()
    return "$page${formatDurationMs(positionMs)}$total"
}

/**
 * 章节标题：多 P 且序号已知时带分 P 序号（`P1 · M　1-30`），否则只给章节名。
 * 标题为空时返回 `未播放`。
 */
fun chapterLabel(title: String?, pageIndex: Int?, pageCount: Int = 1): String {
    val name = title?.takeIf { it.isNotBlank() } ?: return "未播放"
    return if (pageIndex != null && pageIndex > 0 && pageCount > 1) "P$pageIndex · $name" else name
}
