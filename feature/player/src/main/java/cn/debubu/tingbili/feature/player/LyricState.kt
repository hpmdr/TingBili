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
        return lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    }

    companion object {
        fun from(lines: List<LyricLine>, positionMs: Long): LyricState {
            if (lines.isEmpty()) return LyricState(emptyList(), -1)
            return LyricState(lines, LyricState(lines).indexFor(positionMs))
        }
    }
}
