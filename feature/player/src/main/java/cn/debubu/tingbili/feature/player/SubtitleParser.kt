package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.toLyricLines
import cn.debubu.tingbili.data.bilibili.model.LyricLine

/**
 * Parses Bilibili subtitle DTO to lyric lines.
 * Priority: direct body (投稿) > info body > empty (AI fallback requires secondary fetch, stub for now)
 * Delegates to SubtitleDto.toLyricLines() which already implements priority.
 * Also provides helper to parse from manual/ai lists for testing spec compliance.
 */
object SubtitleParser {
    fun parse(dto: SubtitleDto): List<LyricLine> = dto.toLyricLines()

    /**
     * For spec test: picks投稿 (manual) over AI when both provided.
     * Used only in tests to verify priority logic without network.
     */
    fun parse(manual: List<LyricLine>?, ai: List<LyricLine>?): List<LyricLine> {
        return when {
            !manual.isNullOrEmpty() -> manual
            !ai.isNullOrEmpty() -> ai
            else -> emptyList()
        }
    }
}
