package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.data.bilibili.model.LyricLine

/**
 * Holds lyric lines and computes current index for a given position.
 * Used by full-screen player for逐行高亮.
 */
data class LyricState(
    val lines: List<LyricLine> = emptyList(),
    val currentIndex: Int = -1
) {
    fun indexFor(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        // lines 按 timeMs 升序（toLyricLines 已排好）：二分找最后一个 <= position 的。
        // 有声书一集几千行，线性扫每秒都来一次没必要。
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans.coerceAtLeast(0)
    }

    companion object {
        fun from(lines: List<LyricLine>, positionMs: Long): LyricState {
            if (lines.isEmpty()) return LyricState(emptyList(), -1)
            return LyricState(lines, LyricState(lines).indexFor(positionMs))
        }
    }
}
